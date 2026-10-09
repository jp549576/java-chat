import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * One connected client. run() executes in the client's own thread and only READS;
 * send() may be called by ANY thread.
 *
 * New in M3: every request gets exactly ONE reply (OK, USERS or ERROR),
 * and invalid input is answered with ERROR instead of being silently ignored.
 */
final class ClientHandler implements Runnable {

    private final ChatServer server;
    private final Socket socket;
    private final OutputStream out;
    private final String who;
    private volatile String nick;             // null until NICK succeeded
    private boolean quitRequested = false;    // only used by this client's own thread

    ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.who = String.valueOf(socket.getRemoteSocketAddress());
    }

    String nick() {
        return nick;
    }

    void setNick(String nick) {
        this.nick = nick;
    }

    @Override
    public void run() {
        ChatServer.log(who + " connected");
        LineFramer framer = new LineFramer();
        byte[] chunk = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            int n;
            while (!quitRequested && (n = in.read(chunk)) != -1) {
                for (String line : framer.feed(chunk, n)) {
                    handle(line);
                    if (quitRequested) break;      // ignore anything sent after QUIT
                }
            }
        } catch (IOException e) {
            ChatServer.log(who + " connection error: " + e.getMessage());
        } finally {
            server.unregister(this);
            close();
            ChatServer.log(who + " disconnected");
        }
    }

    private void handle(String line) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(line);
        } catch (Json.JsonException e) {
            // Thanks to newline framing the NEXT line is still readable: we can
            // report the error and keep the connection, no need to disconnect.
            send(Protocol.error(Protocol.BAD_REQUEST, "bad JSON: " + e.getMessage()));
            return;
        }
        String type = Json.getString(msg, "type");
        if (type == null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "missing \"type\""));
            return;
        }
        type = type.toUpperCase(Locale.ROOT);       // liberal: "msg" works like "MSG"

        if (nick == null && !type.equals("NICK") && !type.equals("QUIT")) {
            send(Protocol.error(Protocol.NOT_REGISTERED, "send NICK first"));
            return;
        }
        switch (type) {
            case "NICK": handleNick(msg); break;
            case "MSG":  handleMsg(msg);  break;
            case "PRIV": handlePriv(msg); break;
            case "LIST": send(Protocol.users(server.nicks())); break;
            case "QUIT":
                send(Protocol.ok());
                quitRequested = true;               // run() closes the connection
                break;
            default:
                send(Protocol.error(Protocol.BAD_REQUEST, "unknown type '" + type + "'"));
        }
    }

    private void handleNick(Map<String, Object> msg) {
        if (nick != null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "already registered as " + nick));
            return;
        }
        String requested = Json.getString(msg, "nick");
        if (requested != null) requested = requested.strip();
        String problem = Protocol.checkName(requested);
        if (problem != null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "invalid nick: " + problem));
        } else if (!server.register(this, requested)) {
            send(Protocol.error(Protocol.NICK_TAKEN, "nick taken"));
        }
        // on success register() already sent OK
    }

    private void handleMsg(Map<String, Object> msg) {
        String text = Json.getString(msg, "text");
        if (text == null || text.isBlank()) {
            send(Protocol.error(Protocol.BAD_REQUEST, "\"text\" must be a non-empty string"));
            return;
        }
        send(Protocol.ok());
        server.broadcast(Protocol.chat(nick, text, false));
    }

    private void handlePriv(Map<String, Object> msg) {
        String to = Json.getString(msg, "to");
        String text = Json.getString(msg, "text");
        if (to == null || text == null || text.isBlank()) {
            send(Protocol.error(Protocol.BAD_REQUEST, "PRIV needs \"to\" and a non-empty \"text\""));
            return;
        }
        ClientHandler target = server.find(to.strip());
        if (target == null) {
            send(Protocol.error(Protocol.NO_SUCH_USER, "no such user: " + to));
            return;
        }
        target.send(Protocol.chat(nick, text, true));
        send(Protocol.ok());
    }

    /** Writes one message; synchronized so concurrent senders never interleave bytes. */
    synchronized void send(String json) {
        try {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            close();   // our reader thread will notice and clean up
        }
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
