import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer.
 *
 * Java's standard library has no JSON API (Python has "json"), and the exercise
 * forbids external libraries, so this small class does the job.
 *
 *   JSON object  -> LinkedHashMap<String,Object>  (keeps key order: "type" stays first)
 *   JSON array   -> ArrayList<Object>
 *   JSON string  -> String
 *   JSON number  -> Long (integers) or Double
 *   true / false -> Boolean
 *   null         -> null
 *
 * Reading is LIBERAL (any whitespace, any key order, \\uXXXX escapes, extra fields):
 * other groups' languages produce slightly different JSON and we must accept all of it.
 * Writing is STRICT and COMPACT, and never produces a raw newline, because a newline
 * is our message delimiter on the wire.
 *
 * This file is identical in milestones M1..M6.
 */
public final class Json {

    private Json() { }

    /** Thrown for any input that is not valid JSON. */
    public static final class JsonException extends Exception {
        public JsonException(String message, int position) {
            super(message + " (at position " + position + ")");
        }
    }

    /** Max nesting of objects/arrays; stops "[[[[[[..." from overflowing the stack. */
    private static final int MAX_DEPTH = 32;

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) throws JsonException {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.value(0);
        p.skipWhitespace();
        if (p.pos != text.length()) throw p.error("unexpected text after the JSON value");
        return value;
    }

    /** Parses one protocol message, which must be a JSON object: {"type":...}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new JsonException("expected a JSON object {...}", 0);
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        JsonException error(String message) { return new JsonException(message, pos); }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value(int depth) throws JsonException {
            if (depth > MAX_DEPTH) throw error("nested too deeply");
            if (pos >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("unexpected character '" + c + "'");
            }
        }

        Map<String, Object> object(int depth) throws JsonException {
            pos++;                                   // skip '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return map;
            while (true) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') throw error("expected a \"key\"");
                String key = string();
                skipWhitespace();
                if (!consume(':')) throw error("expected ':'");
                skipWhitespace();
                map.put(key, value(depth + 1));
                skipWhitespace();
                if (consume(',')) continue;
                if (consume('}')) return map;
                throw error("expected ',' or '}'");
            }
        }

        List<Object> array(int depth) throws JsonException {
            pos++;                                   // skip '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return list;
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                if (consume(',')) continue;
                if (consume(']')) return list;
                throw error("expected ',' or ']'");
            }
        }

        String string() throws JsonException {
            pos++;                                   // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw error("raw control character inside a string");
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= s.length()) throw error("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        // Python's json.dumps() writes "olá" as "ol\u00e1" by default.
                        // Emoji arrive as two escapes (a UTF-16 surrogate pair); Java
                        // strings are UTF-16 too, so appending both chars rebuilds it.
                        if (pos + 4 > s.length()) throw error("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("bad escape \\" + e);
                }
            }
        }

        Object number() throws JsonException {
            int start = pos;
            boolean isInteger = true;
            consume('-');
            digits();
            if (consume('.')) { isInteger = false; digits(); }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isInteger = false;
                pos++;
                if (!consume('+')) consume('-');
                digits();
            }
            String text = s.substring(start, pos);
            try {
                if (isInteger) {
                    try { return Long.parseLong(text); }
                    catch (NumberFormatException tooBig) { return Double.parseDouble(text); }
                }
                return Double.parseDouble(text);
            } catch (NumberFormatException ex) {
                throw new JsonException("bad number '" + text + "'", start);
            }
        }

        void digits() throws JsonException {
            int start = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            if (pos == start) throw error("expected a digit");
        }

        Object literal(String word, Object value) throws JsonException {
            if (!s.startsWith(word, pos)) throw error("expected '" + word + "'");
            pos += word.length();
            return value;
        }

        boolean consume(char c) {
            if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
            return false;
        }
    }

    // ------------------------------------------------------------------ writing

    /** Serializes Maps, Lists, Strings, Numbers, Booleans and null to compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;   // <- this is what keeps framing safe
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);              // non-ASCII goes out as plain UTF-8
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ helpers

    /** Builds an object from key/value pairs, keeping their order: obj("type","OK"). */
    public static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** The field as a String, or null if it is missing or not a string. */
    public static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
