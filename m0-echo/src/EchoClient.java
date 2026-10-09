import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * M0 - TCP echo client.
 *
 * Reads a line from the keyboard, sends it, and reads the echo back.
 * Because TCP is a byte stream, the echo may come back in several pieces,
 * so we keep calling read() until we got back as many bytes as we sent,
 * and we print how many read() calls that took.
 *
 * Usage: java -cp out EchoClient [host] [port]   (default 127.0.0.1 12345)
 */
public class EchoClient {

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 12345;

        // new Socket(...) performs the TCP 3-way handshake (SYN, SYN-ACK, ACK).
        try (Socket socket = new Socket(host, port);
             BufferedReader keyboard = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Connected " + socket.getLocalSocketAddress()
                    + " -> " + socket.getRemoteSocketAddress());
            System.out.println("Type text and press Enter (Ctrl+D to quit).");

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String line;
            while ((line = keyboard.readLine()) != null) {
                // Always encode explicitly as UTF-8: never trust the platform default.
                byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(data);
                out.flush();

                byte[] echo = new byte[data.length];
                int received = 0, readCalls = 0;
                while (received < echo.length) {
                    int n = in.read(echo, received, echo.length - received);
                    if (n == -1) {
                        System.out.println("Server closed the connection.");
                        return;
                    }
                    received += n;
                    readCalls++;
                }
                System.out.println("echo (" + data.length + " bytes, " + readCalls + " read call(s)): "
                        + new String(echo, StandardCharsets.UTF_8).stripTrailing());
            }
            // Leaving try() closes the socket: the client sends FIN (visible in Wireshark).
        }
    }
}
