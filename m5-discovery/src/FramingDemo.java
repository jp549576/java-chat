import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * M1 showcase: proves the recv buffer handles partial and merged messages.
 *
 * Part 1 (offline) feeds LineFramer by hand and prints what comes out.
 * Part 2 (network) connects to a running server and deliberately sends
 *   a) one message split in 3 TCP segments, with pauses in between,
 *   b) two messages in ONE write,
 *   c) a message cut in the middle of a multi-byte UTF-8 character.
 * Watch the server log ("read N bytes -> K complete messages") and Wireshark.
 *
 * Usage: java -cp out FramingDemo [--host 127.0.0.1] [--port 12345]
 */
public class FramingDemo {

    public static void main(String[] args) throws Exception {
        Cli cli = new Cli(args);
        offlineDemo();
        networkDemo(cli.get("host", "127.0.0.1"), cli.getInt("port", 12345));
    }

    private static void offlineDemo() throws Exception {
        System.out.println("=== Part 1: LineFramer without a network ===");
        LineFramer framer = new LineFramer();
        feed(framer, "{\"type\":\"MSG\",\"te");                      // partial
        feed(framer, "xt\":\"hello\"}\n{\"type\":\"MSG\",\"text\":\"bye\"}\n{\"ty"); // end + full + start
        feed(framer, "pe\":\"MSG\",\"text\":\"done\"}\n");             // end
        System.out.println();
    }

    private static void feed(LineFramer framer, String piece) throws Exception {
        byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
        System.out.println("feed  " + quote(piece));
        System.out.println("  ->  " + framer.feed(bytes, bytes.length) + "   (" + framer.pending() + " bytes kept)");
    }

    private static void networkDemo(String host, int port) throws Exception {
        System.out.println("=== Part 2: sending awkward TCP segments to " + host + ":" + port + " ===");
        try (Socket socket = new Socket(host, port)) {
            // Disable Nagle's algorithm so each write() really leaves as its own TCP segment.
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();

            System.out.println("a) NICK split into 3 segments, 0.5 s apart");
            sendSlowly(out, "{\"type\":\"NI", "CK\",\"nick\":\"fra", "mer\"}\n");

            System.out.println("b) two MSG in a single write()");
            write(out, "{\"type\":\"MSG\",\"text\":\"first\"}\n{\"type\":\"MSG\",\"text\":\"second\"}\n");
            Thread.sleep(500);

            System.out.println("c) MSG \"olá 😀\" cut inside the emoji's 4 UTF-8 bytes");
            byte[] whole = "{\"type\":\"MSG\",\"text\":\"olá 😀\"}\n".getBytes(StandardCharsets.UTF_8);
            int cut = whole.length - 5;                 // inside the emoji (4 bytes) before "}\n
            out.write(Arrays.copyOfRange(whole, 0, cut));
            out.flush();
            Thread.sleep(500);
            out.write(Arrays.copyOfRange(whole, cut, whole.length));
            out.flush();

            System.out.println("Replies from the server (a separate LineFramer on this side):");
            socket.setSoTimeout(1500);
            LineFramer framer = new LineFramer();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    for (String line : framer.feed(chunk, n)) System.out.println("   " + line);
                }
            } catch (SocketTimeoutException done) {
                // no more replies
            }
        }
    }

    private static void sendSlowly(OutputStream out, String... pieces) throws Exception {
        for (String p : pieces) {
            write(out, p);
            Thread.sleep(500);
        }
    }

    private static void write(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String quote(String s) {
        return '"' + s.replace("\n", "\\n") + '"';
    }
}
