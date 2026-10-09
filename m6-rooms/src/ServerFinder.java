import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M5 - client side of UDP discovery: "which chat servers are on this LAN?"
 *
 * Sends DISCOVER to several destinations at once and collects every answer that
 * arrives within a short time:
 *   - 255.255.255.255         the "limited broadcast" (every host on this link);
 *   - each interface's own broadcast address, e.g. 192.168.1.255 -- some systems
 *     (macOS included) only send 255.255.255.255 out of ONE interface;
 *   - 127.0.0.1               a server on this same Mac, even with Wi-Fi off.
 *
 * Each destination gets the request in two dialects, JSON {"type":"DISCOVER"} and
 * plain text DISCOVER, because the assignment does not fix the format and other
 * groups may have chosen either. Duplicate answers are merged.
 */
final class ServerFinder {

    /** One server that answered. host comes from the packet, never from its content. */
    record Found(String host, int port, String name) {
        @Override
        public String toString() {
            return host + ":" + port + (name.isEmpty() ? "" : "  (" + name + ")");
        }
    }

    private ServerFinder() { }

    static List<Found> discover(int udpPort, int waitMillis) throws IOException {
        Map<String, Found> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {   // any free local port
            socket.setBroadcast(true);                        // SO_BROADCAST: needed to send to x.x.x.255
            byte[][] probes = {
                Json.write(Json.obj("type", "DISCOVER")).getBytes(StandardCharsets.UTF_8),
                "DISCOVER".getBytes(StandardCharsets.UTF_8),
            };
            for (InetAddress target : targets()) {
                for (byte[] probe : probes) {
                    try {
                        socket.send(new DatagramPacket(probe, probe.length, target, udpPort));
                    } catch (IOException e) {
                        // e.g. "No route to host" for a destination with no network: try the others
                    }
                }
            }

            byte[] buffer = new byte[2048];
            long deadline = System.currentTimeMillis() + waitMillis;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                socket.setSoTimeout((int) left);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeIsUp) {
                    break;
                }
                Found f = parseReply(packet);
                if (f != null) found.putIfAbsent(f.host() + ":" + f.port(), f);
            }
        }
        return withoutLocalDuplicates(new ArrayList<>(found.values()));
    }

    /** Where to send DISCOVER. */
    static Set<InetAddress> targets() throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) continue;
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                    targets.add(ia.getBroadcast());               // e.g. 192.168.1.255 for en0
                }
            }
        }
        targets.add(InetAddress.getByName("127.0.0.1"));
        return targets;
    }

    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,5})\\b");

    /**
     * Liberal reply parsing: JSON with a "port" field (number or string), or any text
     * containing a port number ("PORT 12345", "12345"). null if it is not a reply.
     */
    static Found parseReply(DatagramPacket packet) {
        String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                StandardCharsets.UTF_8).trim();
        String host = packet.getAddress().getHostAddress();
        if (DiscoveryResponder.isDiscover(text)) return null;      // someone else's probe, not an answer
        Integer port = null;
        String name = "";
        try {
            Map<String, Object> msg = Json.parseObject(text);
            Object p = msg.get("port");
            if (p instanceof Number) port = ((Number) p).intValue();
            if (p instanceof String) port = Integer.parseInt(((String) p).trim());
            Object n = msg.get("name");
            if (n != null) name = String.valueOf(n);
        } catch (Json.JsonException | NumberFormatException notJson) {
            Matcher m = NUMBER.matcher(text);
            if (m.find()) port = Integer.parseInt(m.group(1));
        }
        if (port == null || port < 1 || port > 65535) return null;
        return new Found(host, port, name);
    }

    /**
     * A server on this Mac answers twice: via 127.0.0.1 and via its LAN address.
     * Keep only the LAN entry, since that is the address other people can use too.
     */
    private static List<Found> withoutLocalDuplicates(List<Found> all) {
        List<Found> result = new ArrayList<>();
        for (Found f : all) {
            boolean loopback = f.host().startsWith("127.");
            boolean alsoOnLan = all.stream().anyMatch(o -> !o.host().startsWith("127.")
                    && o.port() == f.port() && o.name().equals(f.name()) && !o.name().isEmpty());
            if (!(loopback && alsoOnLan)) result.add(f);
        }
        return result;
    }
}
