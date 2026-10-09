import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * M5 - server side of UDP discovery.
 *
 * Listens on a UDP port (default 12346) on all interfaces. When a datagram saying
 * DISCOVER arrives (usually sent to the broadcast address by a client that does not
 * know where the server is), it answers the sender directly (unicast) with
 *
 *     {"type":"SERVER","port":12345,"name":"java-chat-ana"}
 *
 * The reply does NOT contain our IP address: the client reads it from the source
 * address of the reply packet. That is always the address that actually reached the
 * client, even if this machine has several interfaces (Wi-Fi, Ethernet, VPN...).
 *
 * UDP keeps message boundaries (one send = one datagram = one receive), so unlike
 * TCP no framing or buffer is needed here.
 */
final class DiscoveryResponder implements Runnable {

    private static final Logger LOG = ChatServer.LOG;

    private final int tcpPort;
    private final String name;
    private final DatagramSocket socket;

    DiscoveryResponder(int udpPort, int tcpPort, String name) throws SocketException {
        this.tcpPort = tcpPort;
        this.name = name;
        socket = new DatagramSocket(null);          // unbound, so we can set options first
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(udpPort)); // all interfaces: receives broadcasts too
    }

    int port() {
        return socket.getLocalPort();
    }

    void start() {
        Thread t = new Thread(this, "discovery");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        socket.close();                              // unblocks receive()
    }

    @Override
    public void run() {
        byte[] buffer = new byte[2048];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                if (!isDiscover(text)) {
                    LOG.fine("discovery: ignored datagram from " + packet.getSocketAddress());
                    continue;                        // never answer answers: no packet storms
                }
                byte[] reply = Json.write(Json.obj("type", "SERVER", "port", tcpPort, "name", name))
                        .getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                LOG.info("discovery: answered " + packet.getSocketAddress());
            } catch (IOException e) {
                if (!socket.isClosed()) LOG.warning("discovery: " + e.getMessage());
            }
        }
    }

    /**
     * The assignment does not fix the request format, so we accept every reasonable
     * dialect: plain text "DISCOVER" (any case, with or without "\n") or a JSON
     * object {"type":"DISCOVER"}.
     */
    static boolean isDiscover(String text) {
        if (text.equalsIgnoreCase("DISCOVER")) return true;
        try {
            Map<String, Object> msg = Json.parseObject(text);
            String type = Json.getString(msg, "type");
            return type != null && type.equalsIgnoreCase("DISCOVER");
        } catch (Json.JsonException e) {
            return false;
        }
    }
}
