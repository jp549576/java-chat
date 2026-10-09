import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * One connected client. run() executes in the client's own thread and only READS;
 * send() may be called by ANY thread (every thread that broadcasts).
 */
final class ClientHandler implements Runnable {

    private final ChatServer server;
    private final Socket socket;
    private final OutputStream out;
    private final String who;                 // "/192.168.1.20:53211", for the log
    private volatile String nick;             // null until NICK; read by other threads

    ClientHandler(ChatServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.who = String.valueOf(socket.getRemoteSocketAddress());
    }

    String nick() {
        return nick;
    }

    @Override
    public void run() {
        ChatServer.log(who + " connected");
        LineFramer framer = new LineFramer();      // one buffer per client: their bytes never mix
        byte[] chunk = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(chunk)) != -1) {
                for (String line : framer.feed(chunk, n)) {
                    handle(line);
                }
            }
        } catch (IOException e) {
            ChatServer.log(who + " connection error: " + e.getMessage());
        } finally {
            server.unregister(this);               // always runs: no "ghost" clients in the list
            close();
            ChatServer.log(who + " disconnected");
        }
    }

    private void handle(String line) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(line);
        } catch (Json.JsonException e) {
            ChatServer.log(who + " ignored, not JSON: " + e.getMessage());
            return;
        }
        String type = Json.getString(msg, "type");
        type = type == null ? "" : type.toUpperCase(Locale.ROOT);

        switch (type) {
            case "NICK": {
                String requested = Json.getString(msg, "nick");
                if (nick != null || requested == null || requested.isBlank()) {
                    ChatServer.log(who + " ignored NICK (already registered, or empty)");
                    return;
                }
                nick = requested.strip();
                server.register(this);
                break;
            }
            case "MSG": {
                String text = Json.getString(msg, "text");
                if (nick == null || text == null) {
                    ChatServer.log(who + " ignored MSG (no NICK yet, or no text)");
                    return;
                }
                send(Protocol.ok());
                server.broadcast(Protocol.chat(nick, text, false));
                break;
            }
            default:
                ChatServer.log(who + " ignored unknown type '" + type + "'");
        }
    }

    /**
     * Writes one message (JSON + "\n").
     * synchronized: two threads may broadcast to this client at the same moment;
     * without the lock their bytes could interleave and corrupt the framing.
     */
    synchronized void send(String json) {
        try {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // The client is gone. Closing the socket makes our reader thread
            // leave its loop, and its finally{} removes us from the list.
            close();
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
