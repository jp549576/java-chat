/**
 * The wire format in ONE place. Every message is one JSON object on one line.
 * Server and client both build their messages here, so a change to the
 * protocol is a change to this file only.
 *
 * M1 messages:
 *   client -> server   NICK  {"type":"NICK","nick":"ana"}
 *   client -> server   MSG   {"type":"MSG","text":"hello"}
 *   server -> client   OK    {"type":"OK"}
 *   server -> client   CHAT  {"type":"CHAT","from":"ana","text":"hello","private":false}
 * New in M2:
 *   server -> client   EVENT {"type":"EVENT","text":"rui joined"}
 */
final class Protocol {

    private Protocol() { }

    // client -> server
    static String nick(String nick) { return Json.write(Json.obj("type", "NICK", "nick", nick)); }
    static String msg(String text)  { return Json.write(Json.obj("type", "MSG", "text", text)); }

    // server -> client
    static String ok() { return Json.write(Json.obj("type", "OK")); }

    static String chat(String from, String text, boolean isPrivate) {
        return Json.write(Json.obj("type", "CHAT", "from", from, "text", text, "private", isPrivate));
    }

    static String event(String text) { return Json.write(Json.obj("type", "EVENT", "text", text)); }
}
