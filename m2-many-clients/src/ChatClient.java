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
 * M2 chat client (M1 + shows EVENT join/leave notices).
 *
 * Two threads:
 *   - main thread:     reads the keyboard and SENDS (only this thread writes);
 *   - receiver thread: READS the socket and prints what arrives.
 * A single thread could not do both, because reading the keyboard blocks
 * and messages from the server must appear while you are typing.
 *
 * Usage: java -cp out ChatClient [--host 127.0.0.1] [--port 12345] [--nick ana]
 */
public class ChatClient {

    /** True between sending NICK and receiving its answer. */
    private static volatile boolean waitingForNickReply = false;
    /** Set when WE close the socket, so the receiver does not report an error. */
    private static volatile boolean quitting = false;

    public static void main(String[] args) throws IOException {
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
        System.out.println("Connected to " + socket.getRemoteSocketAddress()
                + ". Type a message and press Enter. /nick <name>, /quit");
        OutputStream out = socket.getOutputStream();

        Thread receiver = new Thread(() -> receiveLoop(socket), "receiver");
        receiver.setDaemon(true);
        receiver.start();

        send(out, Protocol.nick(nick.strip()));
        waitingForNickReply = true;

        String line;
        while ((line = keyboard.readLine()) != null) {
            if (line.isBlank()) continue;
            if (line.equals("/quit")) break;
            if (line.startsWith("/nick ")) {
                send(out, Protocol.nick(line.substring(6).strip()));
                waitingForNickReply = true;
            } else {
                send(out, Protocol.msg(line));
            }
        }
        quitting = true;
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
            System.out.println("*** server closed the connection");
        } catch (IOException e) {
            if (!quitting) System.out.println("*** connection lost: " + e.getMessage());
        }
        System.exit(0);   // the main thread is stuck reading the keyboard; end the program
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
                System.out.println("[" + msg.get("from") + "] " + msg.get("text"));
                break;
            case "OK":
                if (waitingForNickReply) {
                    waitingForNickReply = false;
                    System.out.println("*** nickname accepted");
                }
                break;   // other OKs only confirm a MSG; the CHAT echo already shows it
            case "EVENT":
                System.out.println("*** " + msg.get("text"));
                break;
            default:
                System.out.println("<<< " + line);
        }
    }
}
