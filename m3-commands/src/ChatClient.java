import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * M3 chat client: commands /list, /msg, /nick, /quit, /help.
 *
 * Two threads:
 *   - main thread:     reads the keyboard and SENDS (only this thread writes);
 *   - receiver thread: READS the socket and prints what arrives.
 *
 * The client never waits for a particular reply: it prints whatever arrives.
 * That keeps it working with servers of other groups whose replies differ a bit.
 *
 * Usage: java -cp out ChatClient [--host 127.0.0.1] [--port 12345] [--nick ana]
 */
public class ChatClient {

    private static final String HELP =
            "Commands: /list   /msg <nick> <text>   /nick <name>   /quit   /help\n"
            + "Anything else is sent to everyone.";

    /** True between sending NICK and receiving its answer. */
    private static volatile boolean waitingForNickReply = false;
    /** Set when WE end the session, so the receiver does not report an error. */
    private static volatile boolean quitting = false;

    public static void main(String[] args) throws Exception {
        Cli cli = new Cli(args);
        String host = cli.get("host", "127.0.0.1");
        int port = cli.getInt("port", 12345);
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String nick = cli.get("nick", null);
        while (nick == null || nick.isBlank()) {
            System.out.print("Nickname: ");
            nick = keyboard.readLine();
            if (nick == null) return;
        }

        Socket socket = new Socket(host, port);
        System.out.println("Connected to " + socket.getRemoteSocketAddress() + "\n" + HELP);
        OutputStream out = socket.getOutputStream();

        Thread receiver = new Thread(() -> receiveLoop(socket), "receiver");
        receiver.setDaemon(true);
        receiver.start();

        waitingForNickReply = true;
        send(out, Protocol.nick(nick.strip()));

        String line;
        while ((line = keyboard.readLine()) != null) {
            if (line.isBlank()) continue;
            if (!line.startsWith("/")) {
                send(out, Protocol.msg(line));
                continue;
            }
            String[] parts = line.split(" ", 3);            // "/msg", "rui", "text with spaces"
            switch (parts[0]) {
                case "/list":
                    send(out, Protocol.list());
                    break;
                case "/msg":
                    if (parts.length < 3 || parts[2].isBlank()) {
                        System.out.println("usage: /msg <nick> <text>");
                    } else {
                        send(out, Protocol.priv(parts[1], parts[2]));
                        System.out.println("[you -> " + parts[1] + "] " + parts[2]);
                    }
                    break;
                case "/nick":
                    if (parts.length < 2) {
                        System.out.println("usage: /nick <name>");
                    } else {
                        waitingForNickReply = true;
                        send(out, Protocol.nick(parts[1]));
                    }
                    break;
                case "/quit":
                    quitting = true;
                    send(out, Protocol.quit());
                    receiver.join(2000);                    // server answers OK and closes
                    socket.close();
                    return;
                default:
                    System.out.println(HELP);
            }
        }
        quitting = true;                                    // Ctrl+D: just close
        socket.close();
    }

    private static void send(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Receiver thread: bytes -> lines (LineFramer) -> JSON -> screen. */
    private static void receiveLoop(Socket socket) {
        LineFramer framer = new LineFramer();
        byte[] chunk = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            int n;
            while ((n = in.read(chunk)) != -1) {
                for (String line : framer.feed(chunk, n)) {
                    display(line);
                }
            }
            if (!quitting) System.out.println("*** server closed the connection");
        } catch (IOException e) {
            if (!quitting) System.out.println("*** connection lost: " + e.getMessage());
        }
        if (!quitting) System.exit(0);   // main thread is blocked on the keyboard: end the program
    }

    /** Prints one server message. Unknown messages are shown raw, never dropped. */
    private static void display(String line) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(line);
        } catch (Json.JsonException e) {
            System.out.println("<<< (not JSON) " + line);
            return;
        }
        String type = String.valueOf(msg.get("type")).toUpperCase(Locale.ROOT);
        switch (type) {
            case "CHAT":
                if (Boolean.TRUE.equals(msg.get("private"))) {
                    System.out.println("[" + msg.get("from") + " -> you] " + msg.get("text"));
                } else {
                    System.out.println("[" + msg.get("from") + "] " + msg.get("text"));
                }
                break;
            case "OK":
                if (waitingForNickReply) {
                    waitingForNickReply = false;
                    System.out.println("*** nickname accepted");
                }
                break;
            case "EVENT":
                System.out.println("*** " + msg.get("text"));
                break;
            case "USERS":
                Object nicks = msg.get("nicks");
                System.out.println("*** online: " + (nicks instanceof java.util.List<?> list
                        ? String.join(", ", list.stream().map(String::valueOf).toList()) : nicks));
                break;
            case "ERROR":
                System.out.println("!!! error " + msg.get("code") + ": " + msg.get("reason"));
                if (waitingForNickReply) {
                    waitingForNickReply = false;
                    System.out.println("    choose another nickname with /nick <name>");
                }
                break;
            default:
                System.out.println("<<< " + line);
        }
    }
}
