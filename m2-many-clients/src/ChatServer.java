import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * M2 - Many clients.
 *
 * What is new compared to M1:
 *   - accept() loop starts ONE THREAD PER CLIENT (ClientHandler), so many
 *     clients are served at the same time;
 *   - a shared list of registered clients, protected by a lock;
 *   - MSG is broadcast to everyone; "ana joined" / "ana left" EVENT notices.
 *
 * Usage: java -cp out ChatServer [--port 12345]
 */
public class ChatServer {

    /**
     * Clients that already sent NICK. Every client thread reads and changes this
     * list, so EVERY access is inside synchronized (clients) { ... }.
     */
    private final List<ClientHandler> clients = new ArrayList<>();
    private final int port;

    public static void main(String[] args) throws IOException {
        Cli cli = new Cli(args);
        new ChatServer(cli.getInt("port", 12345)).run();
    }

    ChatServer(int port) {
        this.port = port;
    }

    void run() throws IOException {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            log("listening on TCP port " + port + " (M2: one thread per client)");
            while (true) {
                Socket socket = server.accept();             // main thread only accepts...
                ClientHandler handler = new ClientHandler(this, socket);
                new Thread(handler, "client-" + socket.getRemoteSocketAddress()).start(); // ...each client gets its own thread
            }
        }
    }

    /**
     * Adds a client that just chose its nickname.
     * The OK is sent while we still hold the lock: no broadcast can reach the new
     * client before its OK, so OK is always the first reply it gets to NICK.
     */
    void register(ClientHandler client) {
        synchronized (clients) {
            clients.add(client);
            client.send(Protocol.ok());
        }
        log(client.nick() + " joined (" + count() + " online)");
        broadcast(Protocol.event(client.nick() + " joined"));
    }

    /** Removes a client (safe to call even if it never registered). */
    void unregister(ClientHandler client) {
        boolean wasRegistered;
        synchronized (clients) {
            wasRegistered = clients.remove(client);
        }
        if (wasRegistered) {
            log(client.nick() + " left (" + count() + " online)");
            broadcast(Protocol.event(client.nick() + " left"));
        }
    }

    /**
     * Sends a message to every registered client (the sender included).
     * We COPY the list under the lock and send OUTSIDE the lock:
     *   - the list may change while we iterate (someone joins/leaves) -> the copy is stable;
     *   - sending can be slow; holding the lock meanwhile would freeze every other thread.
     */
    void broadcast(String json) {
        List<ClientHandler> snapshot;
        synchronized (clients) {
            snapshot = new ArrayList<>(clients);
        }
        for (ClientHandler c : snapshot) {
            c.send(json);
        }
    }

    private int count() {
        synchronized (clients) {
            return clients.size();
        }
    }

    static void log(String text) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                + " [" + Thread.currentThread().getName() + "] " + text);
    }
}
