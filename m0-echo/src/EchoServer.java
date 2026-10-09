import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * M0 - TCP echo server.
 *
 * Accepts ONE client at a time and sends back every byte it receives,
 * exactly as received. No messages, no JSON: just a byte stream.
 *
 * Usage: java -cp out EchoServer [port]        (default port 12345)
 */
public class EchoServer {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 12345;

        try (ServerSocket server = new ServerSocket()) {
            // Must be set BEFORE bind(): lets us restart the server immediately
            // instead of getting "Address already in use" (port stuck in TIME_WAIT).
            server.setReuseAddress(true);
            // No address given = bind on ALL interfaces (0.0.0.0 / ::), so other
            // laptops on the network can connect, not only this Mac (127.0.0.1).
            server.bind(new InetSocketAddress(port));
            System.out.println("Echo server listening on TCP port " + port);

            while (true) {
                // accept() blocks until the TCP 3-way handshake of a new client is done.
                try (Socket client = server.accept()) {
                    serve(client);
                } catch (IOException e) {
                    System.out.println("Client error: " + e.getMessage());
                }
                // Only now do we accept() the next client. A second client that connects
                // meanwhile completes the handshake (the kernel does it) but waits here.
            }
        }
    }

    private static void serve(Socket client) throws IOException {
        System.out.println("Connected:    " + client.getRemoteSocketAddress());
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        byte[] buffer = new byte[1024];
        int n;
        // read() returns how many bytes arrived (1..1024), or -1 when the peer closed
        // its side of the connection (this is Python's "recv() returned b''").
        while ((n = in.read(buffer)) != -1) {
            System.out.println("  read() got " + n + " byte(s): " + visible(buffer, n));
            // Java's OutputStream.write() loops internally until ALL n bytes are handed
            // to the kernel (Python's send() may send only part; sendall() loops).
            out.write(buffer, 0, n);
            out.flush();
        }
        System.out.println("Disconnected: " + client.getRemoteSocketAddress());
    }

    /** Shows the bytes as text, with \n and \r made visible. */
    private static String visible(byte[] data, int n) {
        String s = new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        return '"' + s.replace("\r", "\\r").replace("\n", "\\n") + '"';
    }
}
