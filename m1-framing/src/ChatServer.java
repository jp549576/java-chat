import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M1 - Framing and a protocol.
 *
 * Still ONE client at a time (concurrency is M2). What is new compared to M0:
 *   - bytes are cut into newline-delimited JSON messages by LineFramer;
 *   - the server understands NICK and MSG;
 *   - MSG is answered with OK plus a CHAT, which (with a single client)
 *     goes back to the sender. M2 will send that CHAT to everyone.
 *
 * Usage: java -cp out ChatServer [--port 12345]
 */
public class ChatServer {

    public static void main(String[] args) throws IOException {
        Cli cli = new Cli(args);
        int port = cli.getInt("port", 12345);

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            log("listening on TCP port " + port + " (M1: one client at a time)");
            while (true) {
                try (Socket socket = server.accept()) {
                    serve(socket);
                } catch (IOException e) {
                    log("connection error: " + e.getMessage());
                }
            }
        }
    }

    private static void serve(Socket socket) throws IOException {
        String who = socket.getRemoteSocketAddress().toString();
        log(who + " connected");
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        LineFramer framer = new LineFramer();
        String nick = null;                       // null until the client sends NICK

        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            List<String> lines = framer.feed(chunk, n);
            // This log line is the M1 demo: watch partial and merged messages happen.
            log(who + " read " + n + " byte(s) -> " + lines.size() + " complete message(s), "
                    + framer.pending() + " byte(s) waiting for the rest");

            for (String line : lines) {
                Map<String, Object> msg;
                try {
                    msg = Json.parseObject(line);
                } catch (Json.JsonException e) {
                    log("  ignored, not JSON: " + e.getMessage());   // M3 will answer with ERROR
                    continue;
                }
                String type = Json.getString(msg, "type");
                type = type == null ? "" : type.toUpperCase(Locale.ROOT);   // be liberal: "nick" == "NICK"

                if (type.equals("NICK")) {
                    String requested = Json.getString(msg, "nick");
                    if (requested == null || requested.isBlank()) {
                        log("  ignored NICK without a nick");
                        continue;
                    }
                    nick = requested.strip();
                    log("  NICK -> registered as '" + nick + "'");
                    send(out, Protocol.ok());
                } else if (type.equals("MSG")) {
                    String text = Json.getString(msg, "text");
                    if (nick == null || text == null) {
                        log("  ignored MSG (no NICK yet, or no text)");
                        continue;
                    }
                    log("  MSG from " + nick + ": " + text);
                    send(out, Protocol.ok());
                    send(out, Protocol.chat(nick, text, false));
                } else {
                    log("  ignored unknown message type '" + type + "'");
                }
            }
        }
        log(who + " disconnected");
    }

    /** One message = JSON + "\n", encoded as UTF-8 and written in a single call. */
    private static void send(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void log(String text) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + text);
    }
}
