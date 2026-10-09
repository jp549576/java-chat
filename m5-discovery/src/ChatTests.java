import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Automated tests, with NO external library (JUnit is not in the standard library).
 * Each test starts its own ChatServer in this JVM on a free port (port 0), talks to it
 * through real TCP sockets, and stops it. Run with ./test.sh; exit code 0 = all passed.
 *
 * The same protocol checks also exist for pytest in tests/test_protocol.py; those can
 * be pointed at ANY group's server.
 */
public class ChatTests {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        Logger.getLogger("").setLevel(System.getenv("TEST_LOG") != null ? Level.INFO : Level.OFF);  // TEST_LOG=1 ./test.sh shows server logs

        // --- unit tests: no network
        test("framer: partial, merged, CRLF and empty lines", ChatTests::framerBasics);
        test("framer: UTF-8 character split between two reads", ChatTests::framerSplitUtf8);
        test("framer: line above the limit is rejected", ChatTests::framerLimit);
        test("json: Python-style input (spaces, \\u escapes, emoji)", ChatTests::jsonFromPython);
        test("json: output escapes newlines and never spans two lines", ChatTests::jsonWrite);
        test("json: invalid input and absurd nesting are rejected", ChatTests::jsonInvalid);

        // --- protocol tests: real server, real sockets
        test("NICK -> OK first, then 'joined' EVENT", ChatTests::nickOk);
        test("duplicate nick (any case) -> 409, invalid nick -> 400", ChatTests::nickErrors);
        test("request before NICK -> 401", ChatTests::notRegistered);
        test("MSG -> OK to sender, CHAT to everyone", ChatTests::broadcast);
        test("PRIV -> only the recipient, unknown user -> 404", ChatTests::privateMessage);
        test("LIST -> USERS with every nick", ChatTests::list);
        test("bad JSON / unknown type -> 400 and the connection survives", ChatTests::badInput);
        test("server reassembles partial and merged messages", ChatTests::partialAndMerged);
        test("abrupt disconnect -> others get 'left', server keeps working", ChatTests::abruptDisconnect);
        test("QUIT -> OK, then the server closes", ChatTests::quit);
        test("idle client -> ERROR 408 and disconnected", ChatTests::idleTimeout);
        test("oversized message -> ERROR 413 and disconnected", ChatTests::lineTooLong);
        test("a client that never reads cannot block the others", ChatTests::slowConsumer);
        test("stop() (Ctrl+C) notifies and closes every client", ChatTests::shutdown);

        // --- new in M5
        test("discovery: JSON and plain-text DISCOVER both get our TCP port", ChatTests::discoveryReply);
        test("discovery: ServerFinder finds a server on this machine", ChatTests::discoveryFinder);
        test("discovery: replies in other formats are understood", ChatTests::discoveryParsing);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // =================================================================== unit tests

    static void framerBasics() throws Exception {
        LineFramer f = new LineFramer();
        check(feed(f, "{\"a\":").isEmpty(), "partial line must not be returned");
        checkEquals(List.of("{\"a\":1}", "{\"b\":2}"), feed(f, "1}\n{\"b\":2}\r\n\n{\"c\""));
        checkEquals(List.of("{\"c\":3}"), feed(f, ":3}\n"));
        checkEquals(0, f.pending());
    }

    static void framerSplitUtf8() throws Exception {
        LineFramer f = new LineFramer();
        byte[] all = "olá😀\n".getBytes(StandardCharsets.UTF_8);
        int cut = 5;                                              // inside the emoji
        check(f.feed(Arrays.copyOfRange(all, 0, cut), cut).isEmpty(), "no line yet");
        byte[] rest = Arrays.copyOfRange(all, cut, all.length);
        checkEquals(List.of("olá😀"), f.feed(rest, rest.length));
    }

    static void framerLimit() throws Exception {
        LineFramer f = new LineFramer(10);
        feed(f, "0123456789");                                    // exactly 10: still fine
        try {
            feed(f, "X");
            throw new AssertionError("expected LineTooLongException");
        } catch (LineFramer.LineTooLongException expected) {
            // ok
        }
    }

    static void jsonFromPython() throws Exception {
        // what Python's json.dumps({"type": "MSG", "text": "olá 😀"}) produces:
        Map<String, Object> m = Json.parseObject("{\"type\": \"MSG\", \"text\": \"ol\\u00e1 \\ud83d\\ude00\", \"n\": -1.5e2}");
        checkEquals("MSG", m.get("type"));
        checkEquals("olá 😀", m.get("text"));
        checkEquals(-150.0, m.get("n"));
    }

    static void jsonWrite() throws Exception {
        String s = Json.write(Json.obj("type", "MSG", "text", "a\nb\"c\\", "list", List.of(1, true)));
        checkEquals("{\"type\":\"MSG\",\"text\":\"a\\nb\\\"c\\\\\",\"list\":[1,true]}", s);
        check(s.indexOf('\n') < 0, "no raw newline");
        checkEquals("a\nb\"c\\", Json.parseObject(s).get("text"));
    }

    static void jsonInvalid() {
        for (String bad : new String[]{"", "{", "{\"a\"}", "{\"a\":1,}", "[1 2]", "{\"a\":tru}", "\"x\" y",
                "[".repeat(1000) + "]".repeat(1000)}) {
            try {
                Json.parse(bad);
                throw new AssertionError("accepted invalid JSON: " + bad);
            } catch (Json.JsonException expected) {
                // ok
            }
        }
    }

    // =================================================================== protocol tests

    static void nickOk() throws Exception {
        try (Server s = new Server(); Client a = new Client(s.port)) {
            a.send("type", "NICK", "nick", "ana");
            checkEquals("OK", a.next().get("type"));             // OK must be the FIRST thing
            checkEquals("ana joined", a.next("EVENT").get("text"));
        }
    }

    static void nickErrors() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "Ana"); Client b = new Client(s.port)) {
            b.send("type", "NICK", "nick", "ANA");
            checkEquals(409L, b.next("ERROR").get("code"));
            b.send("type", "NICK", "nick", "has space");
            checkEquals(400L, b.next("ERROR").get("code"));
            b.send("type", "NICK", "nick", "joão");                // non-ASCII letters are fine
            checkEquals("OK", b.next().get("type"));
        }
    }

    static void notRegistered() throws Exception {
        try (Server s = new Server(); Client a = new Client(s.port)) {
            a.send("type", "MSG", "text", "hi");
            checkEquals(401L, a.next("ERROR").get("code"));
        }
    }

    static void broadcast() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana"); Client b = Client.registered(s.port, "rui")) {
            a.send("type", "MSG", "text", "olá");
            checkEquals("OK", a.next("OK").get("type"));
            Map<String, Object> toA = a.next("CHAT");
            Map<String, Object> toB = b.next("CHAT");
            checkEquals("ana", toB.get("from"));
            checkEquals("olá", toB.get("text"));
            checkEquals(false, toB.get("private"));
            checkEquals(toB, toA);                                // sender gets the same copy
        }
    }

    static void privateMessage() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana");
             Client b = Client.registered(s.port, "rui"); Client c = Client.registered(s.port, "eva")) {
            a.send("type", "PRIV", "to", "RUI", "text", "segredo");
            checkEquals("OK", a.next("OK").get("type"));
            Map<String, Object> chat = b.next("CHAT");
            checkEquals(true, chat.get("private"));
            checkEquals("segredo", chat.get("text"));
            a.send("type", "PRIV", "to", "nobody", "text", "x");
            checkEquals(404L, a.next("ERROR").get("code"));
            c.send("type", "LIST");                               // c's next CHAT would be wrong:
            check(c.nextUntilType("USERS").stream().noneMatch(m -> "CHAT".equals(m.get("type"))),
                    "a third client must not see the private message");
        }
    }

    static void list() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana"); Client b = Client.registered(s.port, "rui")) {
            b.send("type", "LIST");
            checkEquals(List.of("ana", "rui"), b.next("USERS").get("nicks"));
        }
    }

    static void badInput() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana")) {
            a.sendRaw("this is not json\n");
            checkEquals(400L, a.next("ERROR").get("code"));
            a.send("type", "DANCE");
            checkEquals(400L, a.next("ERROR").get("code"));
            a.sendRaw("[1,2,3]\n");
            checkEquals(400L, a.next("ERROR").get("code"));
            a.send("type", "LIST");                                // still alive
            checkEquals("USERS", a.next("USERS").get("type"));
        }
    }

    static void partialAndMerged() throws Exception {
        try (Server s = new Server(); Client a = new Client(s.port)) {
            a.socket.setTcpNoDelay(true);
            for (byte b : "{\"type\":\"NICK\",\"nick\":\"zé\"}\n".getBytes(StandardCharsets.UTF_8)) {
                a.out.write(b);                                    // one byte per segment
                a.out.flush();
            }
            checkEquals("OK", a.next().get("type"));
            a.sendRaw("{\"type\":\"MSG\",\"text\":\"1\"}\n{\"type\":\"MSG\",\"text\":\"2\"}\n");  // one write
            checkEquals("1", a.next("CHAT").get("text"));
            checkEquals("2", a.next("CHAT").get("text"));
        }
    }

    static void abruptDisconnect() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana")) {
            Client b = Client.registered(s.port, "rui");
            b.socket.setSoLinger(true, 0);                         // close() sends RST: a "crash"
            b.socket.close();
            checkEquals("rui left", a.nextEventContaining("left").get("text"));
            try (Client c = Client.registered(s.port, "rui")) {  // nick is free again
                c.send("type", "LIST");
                checkEquals(List.of("ana", "rui"), c.next("USERS").get("nicks"));
            }
        }
    }

    static void quit() throws Exception {
        try (Server s = new Server(); Client a = Client.registered(s.port, "ana"); Client b = Client.registered(s.port, "rui")) {
            b.send("type", "QUIT");
            checkEquals("OK", b.next("OK").get("type"));
            check(b.closedWithin(2000), "server must close after QUIT");
            checkEquals("rui left", a.nextEventContaining("left").get("text"));
        }
    }

    static void idleTimeout() throws Exception {
        try (Server s = new Server(1, 65536, 1000); Client a = Client.registered(s.port, "ana")) {
            checkEquals(408L, a.next("ERROR").get("code"));
            check(a.closedWithin(2000), "server must close after the idle timeout");
        }
    }

    static void lineTooLong() throws Exception {
        try (Server s = new Server(30, 1000, 1000); Client a = Client.registered(s.port, "ana")) {
            a.sendRaw("{\"type\":\"MSG\",\"text\":\"" + "x".repeat(5000) + "\"}\n");
            checkEquals(413L, a.next("ERROR").get("code"));
            check(a.closedWithin(2000), "server must close after 413");
        }
    }

    static void slowConsumer() throws Exception {
        try (Server s = new Server(30, 65536, 100); Client fast = Client.registered(s.port, "fast")) {
            // A "frozen" client: tiny receive buffer, and it never reads.
            Socket frozen = new Socket();
            frozen.setReceiveBufferSize(1024);
            frozen.connect(new java.net.InetSocketAddress("127.0.0.1", s.port));
            frozen.getOutputStream().write("{\"type\":\"NICK\",\"nick\":\"frozen\"}\n".getBytes(StandardCharsets.UTF_8));

            // 'fast' floods the room and must still receive every one of its CHATs back.
            AtomicInteger chats = new AtomicInteger();
            AtomicInteger frozenLeft = new AtomicInteger();
            Thread readerThread = new Thread(() -> {
                try {
                    while (true) {
                        Map<String, Object> m = fast.next();
                        if ("CHAT".equals(m.get("type"))) chats.incrementAndGet();
                        if ("frozen left".equals(m.get("text"))) frozenLeft.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                    // socket closed at the end of the test
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Keep sending until the server gives up on 'frozen'. 'fast' paces itself like a
            // real client (waits for its own echoes), so 'fast' itself is never "too slow".
            String big = "y".repeat(2000);
            int sent = 0;
            while (frozenLeft.get() == 0 && sent < 20000) {
                fast.send("type", "MSG", "text", big);
                sent++;
                if (sent % 10 == 0) waitUntil(chats, sent, 5000);
            }
            waitUntil(chats, sent, 5000);
            check(frozenLeft.get() == 1, "the frozen client must have been disconnected");
            checkEquals(sent, chats.get());                        // fast got every single echo
            frozen.close();
        }
    }

    static void shutdown() throws Exception {
        Server s = new Server();
        try (Client a = Client.registered(s.port, "ana"); Client b = new Client(s.port)) {
            s.server.stop();
            checkEquals("server shutting down", a.nextEventContaining("shutting").get("text"));
            check(a.closedWithin(2000), "registered client closed");
            check(b.closedWithin(2000), "unregistered client closed too");
        }
    }

    // =================================================================== M5: discovery

    static void discoveryReply() throws Exception {
        try (Server s = new Server(); java.net.DatagramSocket udp = new java.net.DatagramSocket()) {
            udp.setSoTimeout(2000);
            for (String probe : new String[]{"{\"type\":\"DISCOVER\"}", "DISCOVER\n", "discover"}) {
                byte[] b = probe.getBytes(StandardCharsets.UTF_8);
                udp.send(new java.net.DatagramPacket(b, b.length,
                        java.net.InetAddress.getLoopbackAddress(), s.server.discoveryPort()));
                java.net.DatagramPacket reply = new java.net.DatagramPacket(new byte[2048], 2048);
                udp.receive(reply);
                Map<String, Object> m = Json.parseObject(
                        new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
                checkEquals("SERVER", m.get("type"));
                checkEquals((long) s.port, m.get("port"));
                checkEquals("test-server", m.get("name"));
            }
        }
    }

    static void discoveryFinder() throws Exception {
        try (Server s = new Server()) {
            List<ServerFinder.Found> found = ServerFinder.discover(s.server.discoveryPort(), 1000);
            check(found.stream().anyMatch(f -> f.port() == s.port && f.name().equals("test-server")),
                    "our server not found in " + found);
        }
    }

    static void discoveryParsing() throws Exception {
        java.net.InetAddress from = java.net.InetAddress.getByName("10.0.0.7");
        String[][] cases = {
            {"{\"type\":\"SERVER\",\"port\":5555}", "5555"},
            {"{\"port\": \"6000\", \"name\": \"py\"}", "6000"},
            {"PORT 7000", "7000"},
            {"7001\n", "7001"},
            {"DISCOVER", null},                                    // a probe, not an answer
            {"hello", null},
        };
        for (String[] c : cases) {
            byte[] b = c[0].getBytes(StandardCharsets.UTF_8);
            ServerFinder.Found f = ServerFinder.parseReply(new java.net.DatagramPacket(b, b.length, from, 1));
            if (c[1] == null) {
                check(f == null, "should be ignored: " + c[0]);
            } else {
                check(f != null && f.port() == Integer.parseInt(c[1]) && f.host().equals("10.0.0.7"),
                        "bad parse of " + c[0] + " -> " + f);
            }
        }
    }

    // =================================================================== tiny test framework

    interface TestBody {
        void run() throws Exception;
    }

    static void test(String name, TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("FAIL  " + name + "\n      " + e);
        }
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static void checkEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    static void waitUntil(AtomicInteger counter, int atLeast, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (counter.get() < atLeast && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    static List<String> feed(LineFramer f, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return f.feed(b, b.length);
    }

    /** A server in this JVM on a random free port. */
    static final class Server implements AutoCloseable {
        final ChatServer server;
        final int port;

        Server() throws IOException {
            this(30, 65536, 1000);
        }

        Server(int idleSeconds, int maxLine, int outbox) throws IOException {
            server = new ChatServer(new ChatServer.Config(0, 0, "test-server", idleSeconds, maxLine, outbox));
            server.start();
            port = server.port();
        }

        @Override
        public void close() {
            server.stop();
        }
    }

    /** A raw test client: sends JSON lines and reads replies through its own LineFramer. */
    static final class Client implements AutoCloseable {
        final Socket socket;
        final OutputStream out;
        final InputStream in;
        final LineFramer framer = new LineFramer();
        final Deque<Map<String, Object>> inbox = new ArrayDeque<>();

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);                             // a test never hangs forever
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        static Client registered(int port, String nick) throws Exception {
            Client c = new Client(port);
            c.send("type", "NICK", "nick", nick);
            checkEquals("OK", c.next().get("type"));
            return c;
        }

        void send(Object... keysAndValues) throws IOException {
            sendRaw(Json.write(Json.obj(keysAndValues)) + "\n");
        }

        synchronized void sendRaw(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** The next message from the server (fails on timeout or closed connection). */
        Map<String, Object> next() throws Exception {
            byte[] chunk = new byte[65536];
            while (inbox.isEmpty()) {
                int n = in.read(chunk);
                if (n == -1) throw new AssertionError("connection closed by server");
                for (String line : framer.feed(chunk, n)) inbox.add(Json.parseObject(line));
            }
            return inbox.poll();
        }

        /** Skips messages until one of this type arrives. */
        Map<String, Object> next(String type) throws Exception {
            while (true) {
                Map<String, Object> m = next();
                if (type.equals(m.get("type"))) return m;
            }
        }

        /** Returns all messages up to and including the first one of this type. */
        List<Map<String, Object>> nextUntilType(String type) throws Exception {
            List<Map<String, Object>> seen = new java.util.ArrayList<>();
            while (true) {
                Map<String, Object> m = next();
                seen.add(m);
                if (type.equals(m.get("type"))) return seen;
            }
        }

        Map<String, Object> nextEventContaining(String word) throws Exception {
            while (true) {
                Map<String, Object> m = next("EVENT");
                if (String.valueOf(m.get("text")).contains(word)) return m;
            }
        }

        /** True if the server closes the connection within the given time. */
        boolean closedWithin(int millis) throws IOException {
            socket.setSoTimeout(millis);
            byte[] chunk = new byte[4096];
            try {
                while (in.read(chunk) != -1) {
                    // discard
                }
                return true;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                return true;                                       // reset also means closed
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
