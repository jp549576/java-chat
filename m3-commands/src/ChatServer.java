import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3 - Commands.
 *
 * What is new compared to M2:
 *   - the client list became a MAP nick -> client, which gives
 *       * unique nicknames (atomic check-and-insert under the lock),
 *       * fast lookup of the recipient of a private message (PRIV);
 *   - LIST, PRIV, QUIT; ERROR replies with codes (see Protocol / PROTOCOL.md).
 *
 * Usage: java -cp out ChatServer [--port 12345]
 */
public class ChatServer {

    /**
     * Registered clients, key = nick in lower case ("Ana" and "ana" are the same
     * person, otherwise impersonation is trivial). LinkedHashMap keeps join order
     * for LIST. EVERY access is inside synchronized (clients) { ... }.
     */
    private final Map<String, ClientHandler> clients = new LinkedHashMap<>();
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
            log("listening on TCP port " + port + " (M3: commands)");
            while (true) {
                Socket socket = server.accept();
                ClientHandler handler = new ClientHandler(this, socket);
                new Thread(handler, "client-" + socket.getRemoteSocketAddress()).start();
            }
        }
    }

    private static String key(String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }

    /**
     * Tries to give 'nick' to 'client'. Check, insert and OK happen under ONE lock:
     * two clients asking for "ana" at the same instant cannot both succeed,
     * and OK is always the first thing the new client receives.
     * @return false if the nick is already taken
     */
    boolean register(ClientHandler client, String nick) {
        synchronized (clients) {
            if (clients.containsKey(key(nick))) return false;
            client.setNick(nick);
            clients.put(key(nick), client);
            client.send(Protocol.ok());
        }
        log(nick + " joined (" + nicks().size() + " online)");
        broadcast(Protocol.event(nick + " joined"));
        return true;
    }

    /** Removes a client (safe to call even if it never registered). */
    void unregister(ClientHandler client) {
        String nick = client.nick();
        if (nick == null) return;
        boolean removed;
        synchronized (clients) {
            removed = clients.remove(key(nick), client);
        }
        if (removed) {
            log(nick + " left (" + nicks().size() + " online)");
            broadcast(Protocol.event(nick + " left"));
        }
    }

    /** The client using this nick (any letter case), or null. */
    ClientHandler find(String nick) {
        synchronized (clients) {
            return clients.get(key(nick));
        }
    }

    /** Nicks of all registered clients, in join order. */
    List<String> nicks() {
        List<String> result = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients.values()) result.add(c.nick());
        }
        return result;
    }

    /** Sends to every registered client: copy under the lock, send outside it. */
    void broadcast(String json) {
        List<ClientHandler> snapshot;
        synchronized (clients) {
            snapshot = new ArrayList<>(clients.values());
        }
        for (ClientHandler c : snapshot) {
            c.send(json);
        }
    }

    static void log(String text) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                + " [" + Thread.currentThread().getName() + "] " + text);
    }
}
