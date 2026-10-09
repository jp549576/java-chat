import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M5 chat client: commands /list, /msg, /nick, /quit, /help.
 * M4: Ctrl+C sends QUIT before the program ends.
 * New in M5: without --host the client FINDS servers on the LAN by UDP broadcast
 * (ServerFinder). --scan only lists them, handy on interop day.
 *
 * Two threads:
 *   - main thread:     reads the keyboard and SENDS (only this thread writes);
 *   - receiver thread: READS the socket and prints what arrives.
 *
 * The client never waits for a particular reply: it prints whatever arrives.
 * That keeps it working with servers of other groups whose replies differ a bit.
 *
 * Usage: java -cp out ChatClient [--host 192.168.1.20 [--port 12345]] [--nick ana] [--udp-port 12346]
 *        java -cp out ChatClient --scan
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
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String host = cli.get("host", null);
        int port = cli.getInt("port", 12345);

        if (host == null || cli.has("scan")) {
            int udpPort = cli.getInt("udp-port", 12346);
            System.out.println("Looking for chat servers (UDP broadcast to port " + udpPort + ")...");
            List<ServerFinder.Found> servers = ServerFinder.discover(udpPort, 1500);
            for (int i = 0; i < servers.size(); i++) System.out.println("  " + (i + 1) + ") " + servers.get(i));
            if (cli.has("scan")) return;
            if (servers.isEmpty()) {
                System.out.println("No server answered. Check firewall/Wi-Fi, or use --host <ip>.");
                return;
            }
            int choice = 0;
            if (servers.size() > 1) {
                System.out.print("Connect to which one? [1-" + servers.size() + "] ");
                String answer = keyboard.readLine();
                try {
                    choice = Integer.parseInt(answer.strip()) - 1;
                } catch (RuntimeException e) {
                    choice = -1;
                }
                if (choice < 0 || choice >= servers.size()) {
                    System.out.println("No such server.");
                    return;
                }
            }
            host = servers.get(choice).host();
            port = servers.get(choice).port();
        }

        String nick = cli.get("nick", null);
        while (nick == null || nick.isBlank()) {
            System.out.print("Nickname: ");
            nick = keyboard.readLine();
            if (nick == null) return;
        }

        Socket socket = new Socket(host, port);
        System.out.println("Connected to " + socket.getRemoteSocketAddress() + "\n" + HELP);
        OutputStream out = socket.getOutputStream();

        // Ctrl+C: the JVM runs shutdown hooks before exiting; say goodbye properly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!quitting) {
                quitting = true;
                try {
                    send(out, Protocol.quit());
                } catch (IOException ignored) {
                    // connection already gone
                }
            }
        }, "quit-on-ctrl-c"));

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

    /** synchronized: the main thread and the Ctrl+C hook may both send. */
    private static synchronized void send(OutputStream out, String json) throws IOException {
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
        if (!quitting) {
            quitting = true;              // nothing left to say goodbye to
            System.exit(0);               // main thread is blocked on the keyboard: end the program
        }
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
