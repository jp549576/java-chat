"""
Black-box protocol tests for pytest (the tool named in the assignment).

They talk to the server ONLY through a TCP socket, exactly like any client, so they
work against ANY implementation of the protocol, in any language:

    # our Java server (built and started automatically on a free port):
    python3 -m pytest -v tests/

    # another group's server, for an interop session:
    CHAT_HOST=192.168.1.42 CHAT_PORT=12345 CHAT_UDP_PORT=12346 python3 -m pytest -v tests/

Tests marked "ours" check limits we configured on our own server (idle timeout,
size limit); they are skipped automatically when testing someone else's server.
Tests marked "rooms" (M6) run against another server only with CHAT_ROOMS=1.
"""
import json
import os
import pathlib
import socket
import subprocess
import time
import uuid

import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXTERNAL = "CHAT_HOST" in os.environ
UDP = {"port": int(os.environ.get("CHAT_UDP_PORT", "12346"))}   # filled in by the fixture
ours = pytest.mark.skipif(EXTERNAL, reason="depends on our server's configuration")
# Rooms are OUR M6 extension; another group may have picked a different one.
rooms = pytest.mark.skipif(EXTERNAL and os.environ.get("CHAT_ROOMS") != "1",
                           reason="server may not implement rooms (set CHAT_ROOMS=1 to test)")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def server():
    """(host, port) of the server under test."""
    if EXTERNAL:
        yield os.environ["CHAT_HOST"], int(os.environ.get("CHAT_PORT", "12345"))
        return
    subprocess.run(["bash", str(ROOT / "build.sh")], check=True, capture_output=True)
    port = free_port()
    UDP["port"] = free_port()
    proc = subprocess.Popen(
        ["java", "-Djava.net.preferIPv4Stack=true", "-cp", str(ROOT / "out"), "ChatServer",
         "--port", str(port), "--udp-port", str(UDP["port"]),
         "--idle", "2", "--max-line", "4096", "--no-log-file"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + 10
    while True:                                   # wait until the JVM accepts connections
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            break
        except OSError:
            if time.time() > deadline:
                proc.kill()
                raise RuntimeError("server did not start")
            time.sleep(0.1)
    yield "127.0.0.1", port
    proc.terminate()                              # SIGTERM -> shutdown hook, like Ctrl+C
    proc.wait(timeout=5)


def unique(prefix):
    """A fresh nick per test, so tests sharing one server never collide."""
    return prefix + uuid.uuid4().hex[:6]


class Client:
    """Minimal protocol client with its own recv buffer (framing!)."""

    def __init__(self, addr):
        self.sock = socket.create_connection(addr, timeout=3)
        self.buf = b""

    def send(self, **fields):
        self.send_raw((json.dumps(fields) + "\n").encode())   # json.dumps -> \u escapes

    def send_raw(self, data):
        self.sock.sendall(data)

    def recv(self):
        while b"\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("server closed the connection")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\n", 1)
        return json.loads(line.decode("utf-8"))

    def recv_type(self, *types):
        """Next message of one of these types; skips notifications from other tests."""
        while True:
            msg = self.recv()
            if msg.get("type") in types:
                return msg

    def is_closed(self, wait=3.0):
        self.sock.settimeout(wait)
        try:
            while self.sock.recv(4096):
                pass
            return True
        except socket.timeout:
            return False
        except OSError:
            return True

    def close(self):
        self.sock.close()


@pytest.fixture
def connect(server):
    clients = []

    def _connect(nick=None):
        c = Client(server)
        clients.append(c)
        if nick:
            c.send(type="NICK", nick=nick)
            assert c.recv_type("OK", "ERROR")["type"] == "OK"
        return c

    yield _connect
    for c in clients:
        c.close()


def test_nick_ok_then_joined_event(connect):
    nick = unique("ana")
    c = connect()
    c.send(type="NICK", nick=nick)
    assert c.recv()["type"] == "OK"
    assert c.recv_type("EVENT")["text"] == nick + " joined"


def test_duplicate_nick_any_case_is_409(connect):
    nick = unique("Rui")
    connect(nick)
    other = connect()
    other.send(type="NICK", nick=nick.lower())
    err = other.recv_type("ERROR", "OK")
    assert err["type"] == "ERROR" and err["code"] == 409


def test_request_before_nick_is_401(connect):
    c = connect()
    c.send(type="LIST")
    assert c.recv_type("ERROR", "USERS")["code"] == 401


def test_broadcast_reaches_everyone_with_unicode(connect):
    a, b = connect(unique("a")), connect(unique("b"))
    a.send(type="MSG", text="olá ação 😀")             # sent \u-escaped by Python
    chat = b.recv_type("CHAT")
    assert chat["text"] == "olá ação 😀"
    assert chat["private"] is False


def test_private_message_and_404(connect):
    a_nick, b_nick = unique("a"), unique("b")
    a, b = connect(a_nick), connect(b_nick)
    a.send(type="PRIV", to=b_nick, text="segredo")
    chat = b.recv_type("CHAT")
    assert chat["private"] is True and chat["from"] == a_nick
    a.send(type="PRIV", to="nobody" + unique(""), text="x")
    assert a.recv_type("ERROR")["code"] == 404


def test_list_contains_both(connect):
    a_nick, b_nick = unique("a"), unique("b")
    a, b = connect(a_nick), connect(b_nick)
    a.send(type="LIST")
    nicks = a.recv_type("USERS")["nicks"]
    assert a_nick in nicks and b_nick in nicks


def test_bad_json_keeps_connection_open(connect):
    c = connect(unique("c"))
    c.send_raw(b"this is not json\n")
    assert c.recv_type("ERROR")["code"] == 400
    c.send(type="LIST")
    assert c.recv_type("USERS")["type"] == "USERS"


def test_partial_and_merged_messages(connect, server):
    c = connect()
    c.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    for byte in (json.dumps({"type": "NICK", "nick": unique("frag")}) + "\n").encode():
        c.send_raw(bytes([byte]))                      # one byte per send()
        time.sleep(0.002)
    assert c.recv()["type"] == "OK"
    c.send_raw(b'{"type":"MSG","text":"one"}\n{"type":"MSG","text":"two"}\n')
    assert c.recv_type("CHAT")["text"] == "one"
    assert c.recv_type("CHAT")["text"] == "two"


def test_crlf_line_endings_accepted(connect):
    c = connect()
    c.send_raw(('{"type":"NICK","nick":"%s"}\r\n' % unique("win")).encode())
    assert c.recv()["type"] == "OK"


def test_abrupt_disconnect_announced(connect):
    a = connect(unique("a"))
    b_nick = unique("b")
    b = connect(b_nick)
    b.sock.close()                                     # no QUIT
    while True:
        ev = a.recv_type("EVENT")
        if ev["text"] == b_nick + " left":
            break


def test_quit_then_closed(connect):
    c = connect(unique("q"))
    c.send(type="QUIT")
    assert c.recv_type("OK")["type"] == "OK"
    assert c.is_closed()


@ours
def test_idle_timeout_408(connect):
    c = connect(unique("idle"))
    assert c.recv_type("ERROR")["code"] == 408         # server started with --idle 2
    assert c.is_closed()


@ours
def test_line_too_long_413(connect):
    c = connect(unique("big"))
    c.send(type="MSG", text="x" * 10000)                # server started with --max-line 4096
    assert c.recv_type("ERROR")["code"] == 413
    assert c.is_closed()


# ----------------------------------------------------------------- M5: UDP discovery

@pytest.mark.parametrize("probe", [b'{"type":"DISCOVER"}', b"DISCOVER", b"DISCOVER\n"])
def test_discovery_answers_with_tcp_port(server, probe):
    host, tcp_port = server
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.settimeout(2)
        udp.sendto(probe, (host, UDP["port"]))      # unicast here; clients broadcast
        data, sender = udp.recvfrom(2048)
    reply = json.loads(data.decode("utf-8"))
    assert int(reply["port"]) == tcp_port
    assert sender[0] == socket.gethostbyname(host)   # the IP comes from the packet itself


# ----------------------------------------------------------------- M6: chat rooms

@rooms
def test_rooms_isolate_messages(connect):
    a, b = connect(unique("a")), connect(unique("b"))
    room = unique("room")
    b.send(type="JOIN", room=room)
    assert b.recv_type("OK", "ERROR")["type"] == "OK"
    a.send(type="MSG", text="lobby message")
    b.send(type="MSG", text="room message")
    chat = b.recv_type("CHAT")
    assert chat["text"] == "room message" and chat["room"] == room.lower()


@rooms
def test_rooms_list_shows_only_my_room(connect):
    b_nick = unique("b")
    connect(unique("a"))
    b = connect(b_nick)
    room = unique("room")
    b.send(type="JOIN", room="#" + room)
    b.recv_type("OK")
    b.send(type="LIST")
    users = b.recv_type("USERS")
    assert users["nicks"] == [b_nick] and users["room"] == room.lower()


@rooms
def test_rooms_invalid_name(connect):
    c = connect(unique("c"))
    c.send(type="JOIN", room="no spaces allowed")
    assert c.recv_type("ERROR", "OK")["code"] == 400
