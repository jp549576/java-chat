import java.util.List;

/**
 * The wire format in ONE place (see PROTOCOL.md for the full specification).
 * Every message is one JSON object on one line, UTF-8, ending with "\n".
 *
 *   client -> server   NICK  {"type":"NICK","nick":"ana"}
 *   client -> server   MSG   {"type":"MSG","text":"hello"}
 *   client -> server   PRIV  {"type":"PRIV","to":"rui","text":"hi"}          (new in M3)
 *   client -> server   LIST  {"type":"LIST"}                                 (new in M3)
 *   client -> server   QUIT  {"type":"QUIT"}                                 (new in M3)
 *   server -> client   OK    {"type":"OK"}
 *   server -> client   CHAT  {"type":"CHAT","from":"ana","text":"hello","private":false}
 *   server -> client   USERS {"type":"USERS","nicks":["ana","rui"]}          (new in M3)
 *   server -> client   EVENT {"type":"EVENT","text":"rui joined"}
 *   server -> client   ERROR {"type":"ERROR","code":409,"reason":"nick taken"} (new in M3)
 */
final class Protocol {

    private Protocol() { }

    // Error codes: borrowed from HTTP, so everybody already knows what they mean.
    static final int BAD_REQUEST = 400;      // not JSON, unknown type, missing/invalid field
    static final int NOT_REGISTERED = 401;   // anything except NICK/QUIT before NICK succeeded
    static final int NO_SUCH_USER = 404;     // PRIV to a nick that is not online
    static final int NICK_TAKEN = 409;       // somebody already uses this nick

    static final int MAX_NAME_LENGTH = 20;

    // client -> server
    static String nick(String nick) { return Json.write(Json.obj("type", "NICK", "nick", nick)); }
    static String msg(String text)  { return Json.write(Json.obj("type", "MSG", "text", text)); }
    static String priv(String to, String text) { return Json.write(Json.obj("type", "PRIV", "to", to, "text", text)); }
    static String list() { return Json.write(Json.obj("type", "LIST")); }
    static String quit() { return Json.write(Json.obj("type", "QUIT")); }

    // server -> client
    static String ok() { return Json.write(Json.obj("type", "OK")); }

    static String chat(String from, String text, boolean isPrivate) {
        return Json.write(Json.obj("type", "CHAT", "from", from, "text", text, "private", isPrivate));
    }

    static String event(String text) { return Json.write(Json.obj("type", "EVENT", "text", text)); }

    static String users(List<String> nicks) { return Json.write(Json.obj("type", "USERS", "nicks", nicks)); }

    static String error(int code, String reason) {
        return Json.write(Json.obj("type", "ERROR", "code", code, "reason", reason));
    }

    /**
     * Nickname rule. Returns null if the name is valid, otherwise the reason.
     * Letters and digits of ANY alphabet are allowed (joão, zé, Ελένη), plus '_' and '-'.
     * No spaces: "/msg <nick> <text>" uses the first space to separate nick and text.
     */
    static String checkName(String name) {
        if (name == null || name.isEmpty()) return "must not be empty";
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            return "must be at most " + MAX_NAME_LENGTH + " characters";
        }
        boolean ok = name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-');
        return ok ? null : "only letters, digits, '_' and '-' are allowed";
    }
}
