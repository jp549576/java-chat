import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One connected client, now with TWO threads:
 *
 *   reader thread (run):  reads the socket, handles requests, owns the final close;
 *   writer thread:        takes messages from this client's OUTBOX queue and writes them.
 *
 * Why the outbox (new in M4)? In M3, broadcast() wrote directly to every socket.
 * If one client stops reading (frozen laptop, full Wi-Fi buffer), write() blocks
 * forever and the thread that was broadcasting -- i.e. some OTHER client -- freezes.
 * Java sockets have a read timeout (setSoTimeout) but no write timeout, so we cannot
 * just "use a timeout" when sending. Now send() only puts the message in a bounded
 * queue and returns immediately. If the queue is full, that client is too slow and
 * is disconnected; nobody else is affected.
 *
 * Also new: idle timeout (ERROR 408), size limit (ERROR 413), quiet handling of
 * abrupt disconnects, ERROR 500 instead of a dead thread if handling a message
 * throws, and a graceful close that does not lose the last message.
 */
final class ClientHandler implements Runnable {

    private static final Logger LOG = ChatServer.LOG;
    /** "Poison pill": when the writer takes this exact object, it finishes. */
    private static final String END_OF_OUTPUT = new String("<end>");

    private final ChatServer server;
    private final Socket socket;
    private final String who;
    private final BlockingQueue<String> outbox;
    private final Thread writer;
    private volatile Thread reader;
    private final AtomicBoolean closing = new AtomicBoolean(false);  // no new messages accepted
    private final AtomicBoolean closed = new AtomicBoolean(false);   // socket closed
    private volatile String nick;
    /** Current room (M6). Changed only by ChatServer.changeRoom(), under its lock. */
    private volatile String room = Protocol.DEFAULT_ROOM;
    private boolean quitRequested = false;                           // reader thread only

    ClientHandler(ChatServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
        this.who = String.valueOf(socket.getRemoteSocketAddress());
        this.outbox = new ArrayBlockingQueue<>(server.config().outboxLimit());
        this.writer = new Thread(this::writeLoop, "writer-" + who);
        this.writer.setDaemon(true);
    }

    String nick() {
        return nick;
    }

    void setNick(String nick) {
        this.nick = nick;
    }

    String room() {
        return room;
    }

    void setRoom(String room) {
        this.room = room;
    }

    // ------------------------------------------------------------------ reader thread

    @Override
    public void run() {
        reader = Thread.currentThread();
        writer.start();
        LOG.info(who + " connected");
        try {
            // read() now gives up after this many ms without data -> SocketTimeoutException
            socket.setSoTimeout(server.config().idleTimeoutSeconds() * 1000);
            readLoop();                                   // returns on EOF or after QUIT
            finish(null);
        } catch (SocketTimeoutException e) {
            LOG.info(who + " idle for " + server.config().idleTimeoutSeconds() + " s, disconnecting");
            finish(Protocol.error(Protocol.IDLE_TIMEOUT, "idle timeout"));
        } catch (LineFramer.LineTooLongException e) {
            LOG.warning(who + " " + e.getMessage() + ", disconnecting");
            // We cannot find the start of the next message any more: report and close.
            finish(Protocol.error(Protocol.LINE_TOO_LONG, e.getMessage()));
        } catch (IOException e) {
            // Abrupt disconnect ("Connection reset", "Broken pipe") or we closed the socket.
            // Normal life for a server: one INFO line, no stack trace.
            if (!closed.get()) LOG.info(who + " connection lost: " + e.getMessage());
        } finally {
            server.connectionEnded(this);                 // unregister + "<nick> left"
            if (closing.get()) awaitWriter(1000);         // graceful end: let queued messages go out
            closeNow();
            LOG.info(who + " disconnected");
        }
    }

    private void readLoop() throws IOException {
        LineFramer framer = new LineFramer(server.config().maxLineBytes());
        InputStream in = socket.getInputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            for (String line : framer.feed(chunk, n)) {
                try {
                    handle(line);
                } catch (RuntimeException bug) {
                    // A bug while handling ONE message must not kill the whole connection.
                    LOG.log(Level.SEVERE, who + " internal error on: " + line, bug);
                    send(Protocol.error(Protocol.INTERNAL_ERROR, "internal server error"));
                }
                if (quitRequested) return;                // ignore anything after QUIT
            }
        }
    }

    /**
     * Graceful end: queue a last message (may be null), let the writer send it and then
     * a FIN, and keep reading (discarding) for up to 1 s. Closing a socket that still
     * has unread incoming data makes the kernel send RST, and an RST can destroy our
     * last message before the client reads it (exactly the ERROR 413 case).
     */
    private void finish(String lastMessage) {
        sendAndClose(lastMessage);
        long deadline = System.currentTimeMillis() + 1000;
        byte[] trash = new byte[4096];
        try {
            InputStream in = socket.getInputStream();
            while (System.currentTimeMillis() < deadline) {
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                if (in.read(trash) == -1) break;          // client closed too: done
            }
        } catch (IOException ignored) {
            // timeout or socket closed: done either way
        }
    }

    private void handle(String line) {
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(line);
        } catch (Json.JsonException e) {
            send(Protocol.error(Protocol.BAD_REQUEST, "bad JSON: " + e.getMessage()));
            return;
        }
        String type = Json.getString(msg, "type");
        if (type == null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "missing \"type\""));
            return;
        }
        type = type.toUpperCase(Locale.ROOT);

        if (nick == null && !type.equals("NICK") && !type.equals("QUIT")) {
            send(Protocol.error(Protocol.NOT_REGISTERED, "send NICK first"));
            return;
        }
        switch (type) {
            case "NICK": handleNick(msg); break;
            case "MSG":  handleMsg(msg);  break;
            case "PRIV": handlePriv(msg); break;
            case "LIST": send(Protocol.users(server.nicksInRoom(room), room)); break;
            case "JOIN": handleJoin(msg); break;
            case "LEAVE": server.changeRoom(this, Protocol.DEFAULT_ROOM); break;
            case "QUIT":
                send(Protocol.ok());
                quitRequested = true;
                break;
            default:
                send(Protocol.error(Protocol.BAD_REQUEST, "unknown type '" + type + "'"));
        }
    }

    private void handleNick(Map<String, Object> msg) {
        if (nick != null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "already registered as " + nick));
            return;
        }
        String requested = Json.getString(msg, "nick");
        if (requested != null) requested = requested.strip();
        String problem = Protocol.checkName(requested);
        if (problem != null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "invalid nick: " + problem));
        } else if (!server.register(this, requested)) {
            send(Protocol.error(Protocol.NICK_TAKEN, "nick taken"));
        }
    }

    private void handleMsg(Map<String, Object> msg) {
        String text = Json.getString(msg, "text");
        if (text == null || text.isBlank()) {
            send(Protocol.error(Protocol.BAD_REQUEST, "\"text\" must be a non-empty string"));
            return;
        }
        send(Protocol.ok());
        server.broadcastToRoom(room, Protocol.roomChat(nick, text, room));
    }

    private void handleJoin(Map<String, Object> msg) {
        String requested = Protocol.normalizeRoom(Json.getString(msg, "room"));
        String problem = Protocol.checkName(requested);              // same rules as nicks
        if (problem != null) {
            send(Protocol.error(Protocol.BAD_REQUEST, "invalid room: " + problem));
            return;
        }
        server.changeRoom(this, requested);
    }

    private void handlePriv(Map<String, Object> msg) {
        String to = Json.getString(msg, "to");
        String text = Json.getString(msg, "text");
        if (to == null || text == null || text.isBlank()) {
            send(Protocol.error(Protocol.BAD_REQUEST, "PRIV needs \"to\" and a non-empty \"text\""));
            return;
        }
        ClientHandler target = server.find(to.strip());
        if (target == null) {
            send(Protocol.error(Protocol.NO_SUCH_USER, "no such user: " + to));
            return;
        }
        target.send(Protocol.chat(nick, text, true));
        send(Protocol.ok());
    }

    // ------------------------------------------------------------------ sending (any thread)

    /** Queues a message and returns immediately; never blocks the calling thread. */
    void send(String json) {
        if (closing.get()) return;
        if (!outbox.offer(json)) {
            LOG.warning(who + " is too slow (" + server.config().outboxLimit()
                    + " messages waiting), disconnecting it");
            closeNow();
        }
    }

    /** Queues a last message (or null) and asks the writer to finish after it. */
    void sendAndClose(String lastMessage) {
        if (!closing.compareAndSet(false, true)) return;
        boolean queued = (lastMessage == null || outbox.offer(lastMessage)) && outbox.offer(END_OF_OUTPUT);
        if (!queued) closeNow();
    }

    // ------------------------------------------------------------------ writer thread

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (true) {
                String json = outbox.take();
                if (json == END_OF_OUTPUT) {              // identity check: our own pill object
                    socket.shutdownOutput();              // FIN: "no more data from the server"
                    return;
                }
                out.write((json + "\n").getBytes(StandardCharsets.UTF_8));  // may block: only this thread
                out.flush();
            }
        } catch (InterruptedException | IOException e) {
            closeNow();                                   // client vanished: reader will notice
        }
    }

    // ------------------------------------------------------------------ closing

    /** Closes immediately (idempotent, any thread). The reader then leaves its loop. */
    void closeNow() {
        closing.set(true);
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
            writer.interrupt();                           // wakes it if it waits in take()
            outbox.clear();
        }
    }

    /** Waits until the reader thread has finished (used by ChatServer.stop()). */
    void awaitFinished(long millis) {
        join(reader, millis);
    }

    private void awaitWriter(long millis) {
        join(writer, millis);
    }

    private static void join(Thread t, long millis) {
        if (t == null || t == Thread.currentThread()) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
