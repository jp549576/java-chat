import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The "recv buffer": turns a TCP byte stream back into messages.
 *
 * TCP has no message boundaries. One read() can return
 *   - half a message           {"type":"MSG","te        (partial)
 *   - two messages glued        {...}\n{...}\n           (merged)
 *   - one and a half messages   {...}\n{"ty              (both)
 * Our framing rule: every message ends with the byte '\n'.
 * We append each chunk to a buffer, cut out every complete line,
 * and KEEP the incomplete tail for the next read().
 *
 * We split on the BYTE 0x0A before decoding UTF-8. That is safe because in UTF-8
 * the byte 0x0A never occurs inside a multi-byte character, while decoding each
 * chunk separately would break characters like "ã" or emoji cut in half by TCP.
 *
 * New in M4: a maximum line length. Without it, a client that never sends '\n'
 * makes the buffer grow until the server runs out of memory.
 */
public final class LineFramer {

    /** Thrown when a message is longer than the limit. The stream cannot be trusted any more. */
    public static final class LineTooLongException extends IOException {
        public LineTooLongException(int limit) {
            super("message longer than " + limit + " bytes");
        }
    }

    private final int maxLineBytes;
    private byte[] buffer = new byte[4096];
    private int length = 0;                     // bytes currently waiting in the buffer

    /** Framer with a generous 1 MiB limit (used by clients). */
    public LineFramer() {
        this(1 << 20);
    }

    public LineFramer(int maxLineBytes) {
        this.maxLineBytes = maxLineBytes;
    }

    /** Adds n freshly received bytes; returns every line that is now complete. */
    public List<String> feed(byte[] data, int n) throws LineTooLongException {
        if (length + n > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(length + n, buffer.length * 2));
        }
        System.arraycopy(data, 0, buffer, length, n);
        length += n;

        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < length; i++) {
            if (buffer[i] == '\n') {
                if (i - start > maxLineBytes) throw new LineTooLongException(maxLineBytes);
                int end = i;
                if (end > start && buffer[end - 1] == '\r') end--;   // accept "\r\n" too (Windows)
                String line = new String(buffer, start, end - start, StandardCharsets.UTF_8);
                if (!line.isBlank()) lines.add(line);                // ignore empty lines
                start = i + 1;
            }
        }
        // Move the incomplete tail to the front; it waits for the next read().
        System.arraycopy(buffer, start, buffer, 0, length - start);
        length -= start;
        if (length > maxLineBytes) throw new LineTooLongException(maxLineBytes);
        return lines;
    }

    /** How many bytes of an unfinished message are waiting. */
    public int pending() {
        return length;
    }
}
