import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * M6 - Extension C: chat rooms (/join, /leave).
 *
 * What is new compared to M5:
 *   - every client has a current room (ClientHandler.room), "lobby" at first;
 *   - MSG and LIST work inside the sender's room (broadcastToRoom, nicksInRoom);
 *   - changeRoom() moves a client and tells both rooms.
 * There is NO separate "rooms" table: a room exists while somebody is in it.
 * Nothing to create, nothing to delete, nothing that can get out of sync.
 * Connect/disconnect notices and PRIV still work server-wide, as in M3.
 *
 * Usage: java -cp out ChatServer [--port 12345] [--udp-port 12346] [--name my-server]
 *                                [--idle 300] [--max-line 65536] [--outbox 1000] [--no-log-file]
 */
public class ChatServer {

    static final Logger LOG = Logger.getLogger("chat");

    /** All tunable settings in one place. Tests use small values. */
    record Config(int port, int udpPort, String name,
                  int idleTimeoutSeconds, int maxLineBytes, int outboxLimit) { }

    private final Config config;
    /** Registered clients, key = nick in lower case. Guarded by synchronized (clients). */
    private final Map<String, ClientHandler> clients = new LinkedHashMap<>();
    /** EVERY open connection, also before NICK, so stop() can close them all. */
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private DiscoveryResponder discovery;
    private Thread acceptThread;
    private volatile boolean running;

    public static void main(String[] args) throws Exception {
        Cli cli = new Cli(args);
        Config config = new Config(
                cli.getInt("port", 12345),
                cli.getInt("udp-port", 12346),
                // Not InetAddress.getLocalHost().getHostName(): on macOS that can take
                // 5 s (DNS lookup of the Mac's own name) and returns odd names.
                cli.get("name", "java-chat-" + System.getProperty("user.name")),
                cli.getInt("idle", 300),
                cli.getInt("max-line", 64 * 1024),
                cli.getInt("outbox", 1000));
        setupLogging(!cli.has("no-log-file"));

        ChatServer server = new ChatServer(config);
        server.start();
        // Ctrl+C (SIGINT) or "kill" (SIGTERM): the JVM runs shutdown hooks before exiting.
        // Printed directly: java.util.logging has its own shutdown hook that may already
        // have closed the log handlers (hooks run in parallel, in no fixed order).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutdown requested (Ctrl+C or kill): closing all connections...");
            server.stop();
            System.err.println("bye");
        }, "shutdown"));
        server.acceptThread.join();
    }

    ChatServer(Config config) {
        this.config = config;
    }

    Config config() {
        return config;
    }

    /** Binds the port and starts the accept thread; returns immediately. */
    void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.port()));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "accept");
        acceptThread.start();
        discovery = new DiscoveryResponder(config.udpPort(), port(), config.name());
        discovery.start();
        LOG.info("listening on TCP port " + port() + ", discovery on UDP port " + discoveryPort()
                + " as '" + config.name() + "' (idle timeout " + config.idleTimeoutSeconds() + " s)");
    }

    /** The real UDP discovery port (tests start it on port 0). */
    int discoveryPort() {
        return discovery.port();
    }

    /** The real port, useful when started with port 0 ("any free port"). */
    int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(this, socket);
                connections.add(handler);
                if (!running) {                          // stop() ran while we were accepting:
                    handler.closeNow();                  // don't leak this last connection
                    break;
                }
                new Thread(handler, "client-" + socket.getRemoteSocketAddress()).start();
            } catch (IOException e) {
                if (!running) break;                     // stop() closed the socket: normal exit
                LOG.warning("accept failed: " + e.getMessage());
                sleep(100);                              // e.g. "too many open files": don't spin
            }
        }
    }

    /** Clean shutdown: stop accepting, tell every client, give writers a moment, close. */
    void stop() {
        if (!running) return;
        running = false;
        LOG.info("stopping: closing " + connections.size() + " connection(s)");
        if (discovery != null) discovery.stop();
        try {
            serverSocket.close();                        // unblocks accept()
        } catch (IOException ignored) {
            // already closed
        }
        for (ClientHandler c : connections) {
            c.sendAndClose(Protocol.event("server shutting down"));
        }
        long deadline = System.currentTimeMillis() + 1500;
        for (ClientHandler c : connections) {
            c.awaitFinished(Math.max(1, deadline - System.currentTimeMillis()));
            c.closeNow();
        }
    }

    // ------------------------------------------------------------------ client registry

    private static String key(String nick) {
        return nick.toLowerCase(Locale.ROOT);
    }

    /** Check + insert + OK under ONE lock (see M3). @return false if the nick is taken */
    boolean register(ClientHandler client, String nick) {
        synchronized (clients) {
            if (clients.containsKey(key(nick))) return false;
            client.setNick(nick);
            clients.put(key(nick), client);
            client.send(Protocol.ok());                  // only queues: never blocks under the lock
        }
        LOG.info(nick + " joined (" + nicks().size() + " online)");
        broadcast(Protocol.event(nick + " joined"));
        return true;
    }

    /** Called by a ClientHandler when its connection is over, whatever the reason. */
    void connectionEnded(ClientHandler client) {
        connections.remove(client);
        String nick = client.nick();
        if (nick == null) return;
        boolean removed;
        synchronized (clients) {
            removed = clients.remove(key(nick), client);
        }
        if (removed) {
            LOG.info(nick + " left (" + nicks().size() + " online)");
            broadcast(Protocol.event(nick + " left"));
        }
    }

    ClientHandler find(String nick) {
        synchronized (clients) {
            return clients.get(key(nick));
        }
    }

    List<String> nicks() {
        List<String> result = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients.values()) result.add(c.nick());
        }
        return result;
    }

    /**
     * Moves a client to another room. The change and the OK happen under the lock,
     * like NICK: the client gets OK before any message of its new room.
     */
    void changeRoom(ClientHandler client, String newRoom) {
        String oldRoom;
        synchronized (clients) {
            oldRoom = client.room();
            client.send(Protocol.ok());
            if (oldRoom.equals(newRoom)) return;         // already there: OK, nothing changes
            client.setRoom(newRoom);
        }
        LOG.info(client.nick() + " moved #" + oldRoom + " -> #" + newRoom);
        broadcastToRoom(oldRoom, Protocol.event(client.nick() + " left #" + oldRoom));
        broadcastToRoom(newRoom, Protocol.event(client.nick() + " joined #" + newRoom));
    }

    /** Like broadcast(), but only to the clients currently in 'room'. */
    void broadcastToRoom(String room, String json) {
        List<ClientHandler> snapshot = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients.values()) {
                if (c.room().equals(room)) snapshot.add(c);
            }
        }
        for (ClientHandler c : snapshot) {
            c.send(json);
        }
    }

    /** Nicks of the clients in one room, in join order. */
    List<String> nicksInRoom(String room) {
        List<String> result = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients.values()) {
                if (c.room().equals(room)) result.add(c.nick());
            }
        }
        return result;
    }

    /** Copy under the lock, send outside it. send() only queues, so this never blocks. */
    void broadcast(String json) {
        List<ClientHandler> snapshot;
        synchronized (clients) {
            snapshot = new ArrayList<>(clients.values());
        }
        for (ClientHandler c : snapshot) {
            c.send(json);
        }
    }

    // ------------------------------------------------------------------ logging

    /** One line per record, to the terminal and (optionally) appended to server.log. */
    static void setupLogging(boolean toFile) throws IOException {
        Formatter oneLine = new Formatter() {
            @Override
            public String format(LogRecord r) {
                return String.format("%1$tF %1$tT.%1$tL %2$-7s [%3$s] %4$s%n",
                        r.getMillis(), r.getLevel(), Thread.currentThread().getName(), r.getMessage());
            }
        };
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) root.removeHandler(h);   // drop the 2-line default format
        Handler console = new ConsoleHandler();
        console.setEncoding("UTF-8");
        console.setFormatter(oneLine);
        root.addHandler(console);
        if (toFile) {
            Handler file = new FileHandler("server.log", true);     // true = append
            file.setEncoding("UTF-8");
            file.setFormatter(oneLine);
            root.addHandler(file);
        }
        root.setLevel(Level.INFO);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
