# M0 — Echo warm-up

**Goal:** a TCP echo server and client, and watching the TCP handshake and the
data in Wireshark.

| File | Role |
|---|---|
| `src/EchoServer.java` | accepts one client at a time and sends back every byte it reads |
| `src/EchoClient.java` | sends what you type and reads the echo back |
| `build.sh`, `server.sh`, `client.sh` | compile / run (the run scripts compile automatically when a source changed) |

## Run it

```bash
./server.sh            # terminal 1   (or ./server.sh 5555 for another port)
./client.sh            # terminal 2   (or ./client.sh 192.168.1.20 12345)
```

Type `hello`, press Enter, and the client prints
`echo (6 bytes, 1 read call(s)): hello`. The server prints
`read() got 6 byte(s): "hello\n"`. Quit the client with **Ctrl+D**, the server with **Ctrl+C**.

The server also works with tools that are not ours, which already shows that
the protocol (here: "raw bytes") is what matters, not the language:

```bash
nc 127.0.0.1 12345     # macOS netcat: type text, get it back
```

## Showcase: Wireshark on macOS

1. Install Wireshark and, when the installer offers it, **ChmodBPF** (lets you capture
   without `sudo`).
2. Capture on **`Loopback: lo0`** if client and server run on the same Mac
   (macOS can capture loopback natively; Windows needs Npcap for that),
   or on **`Wi-Fi: en0`** if they run on two machines.
3. Display filter: `tcp.port == 12345`.
4. Start the client, type two lines, press Ctrl+D. You will see:
   - **SYN, SYN-ACK, ACK** — the 3-way handshake, done inside `new Socket(...)`
     on the client and completed by the kernel before `accept()` returns;
   - **PSH, ACK** with your bytes (right-click › Follow › TCP Stream shows the text),
     then the same bytes coming back;
   - **FIN, ACK** in both directions when the client closes.
5. Experiment worth showing: start a **second** client while the first is
   connected. Wireshark shows its handshake completing, yet the server ignores it
   until the first client leaves: the kernel accepts connections into a *backlog*,
   but our single-threaded program only calls `accept()` when it is free.
   That is exactly the problem M2 solves.

## Design choices (and why)

- **`setReuseAddress(true)` before `bind()`.** After a restart the old port can sit
  in TIME_WAIT for a minute and `bind()` fails with "Address already in use"
  (a typical problem on the slides). The option must be set before binding, so
  we create the `ServerSocket` unbound, set it, then bind.
- **Bind to all interfaces** (`new InetSocketAddress(port)` with no address), not
  `127.0.0.1`: other groups' laptops must be able to connect.
- **Port 12345, not 5000.** On macOS 12+ port 5000 (and 7000) is taken by the
  AirPlay Receiver, a classic "why does my server not start on a Mac" trap.
  Every program accepts another port.
- **Echo raw bytes, not lines.** The server writes back exactly the `n` bytes each
  `read()` returned. That keeps M0 honest about what TCP is: a byte stream.
- **The client reads until it has as many bytes as it sent** and counts the
  `read()` calls. Usually it is 1, but nothing guarantees it; this loop is the
  first hint of the framing problem M1 solves properly.
- **`read()` returning `-1`** is Java's version of Python's `recv()` returning
  `b""`: the peer closed. The loop stops on it instead of spinning forever.
- **`OutputStream.write()` writes everything.** Unlike Python's `send()`, which may
  send only part of the buffer, Java's blocking `write()` loops internally until
  all bytes are handed to the kernel. So the "partial send" problem from the
  slides is handled by the JDK; we only must call `flush()`.
- **Explicit UTF-8 everywhere** (`getBytes(UTF_8)`, `InputStreamReader(System.in, UTF_8)`),
  never the platform default, and the scripts force UTF-8 on the terminal. A
  Portuguese class will type `ç` and `ã` on day one.
- **One client at a time, on purpose.** Simplest possible server; its limitation
  is the motivation for M2.
