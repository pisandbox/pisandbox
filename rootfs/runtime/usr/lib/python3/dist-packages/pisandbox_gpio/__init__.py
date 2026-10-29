"""PiSandbox control-socket client (shared by the RPi.GPIO shim, the gpiozero pin factory and the
`gpio` / `browse` commands). Talks to the PiSandbox Android app, which draws the virtual 40-pin
header. Address: $PISANDBOX_CTL = "unix:@app.pisandbox.ctl" (default, abstract Unix socket) or
"tcp:HOST:PORT" (tests)."""
import os
import queue
import socket
import threading
import traceback

__version__ = "0.5.2"
DEFAULT_ADDR = "unix:@app.pisandbox.ctl"

# Physical header pin -> BCM GPIO (40-pin header, all models since the B+)
BOARD_TO_BCM = {3: 2, 5: 3, 7: 4, 8: 14, 10: 15, 11: 17, 12: 18, 13: 27, 15: 22, 16: 23, 18: 24,
                19: 10, 21: 9, 22: 25, 23: 11, 24: 8, 26: 7, 27: 0, 28: 1, 29: 5, 31: 6, 32: 12,
                33: 13, 35: 19, 36: 16, 37: 26, 38: 20, 40: 21}
BCM_TO_BOARD = {v: k for k, v in BOARD_TO_BCM.items()}


class CtlError(RuntimeError):
    pass


class Connection:
    def __init__(self, client="python", addr=None):
        addr = addr or os.environ.get("PISANDBOX_CTL", DEFAULT_ADDR)
        try:
            if addr.startswith("tcp:"):
                host, port = addr[4:].rsplit(":", 1)
                s = socket.create_connection((host, int(port)), timeout=5)
            else:
                path = addr[5:] if addr.startswith("unix:") else addr
                if path.startswith("@"):
                    path = "\0" + path[1:]
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.settimeout(5)
                s.connect(path)
        except OSError as e:
            raise CtlError(
                f"cannot reach the PiSandbox app's GPIO/control socket ({addr}): {e}. "
                "Run this inside the PiSandbox app (it serves the virtual GPIO header).") from None
        s.settimeout(None)
        self._sock = s
        self._rfile = s.makefile("r", encoding="utf-8", newline="\n")
        self._lock = threading.Lock()
        self._replies = queue.Queue()
        self._events = queue.Queue()
        self._handlers = {}
        self._stale = 0
        self.closed = False
        threading.Thread(target=self._reader, name="pisandbox-ctl-reader", daemon=True).start()
        threading.Thread(target=self._dispatcher, name="pisandbox-ctl-events", daemon=True).start()
        self.server_info = self.call("hello", client)

    def _reader(self):
        try:
            for line in self._rfile:
                line = line.rstrip("\n")
                if line.startswith("ev "):
                    _, pin, val = line.split()
                    self._events.put((int(pin), int(val)))
                else:
                    self._replies.put(line)
        except Exception:
            pass
        self.closed = True
        self._replies.put("err connection to the PiSandbox app closed")

    def _dispatcher(self):
        while True:
            pin, val = self._events.get()
            h = self._handlers.get(pin)
            if h is not None:
                try:
                    h(val)
                except Exception:
                    traceback.print_exc()

    def on_event(self, pin, handler):
        if handler is None:
            self._handlers.pop(pin, None)
        else:
            self._handlers[pin] = handler

    def call(self, *args):
        line = " ".join(str(a) for a in args)
        with self._lock:
            if self.closed:
                raise CtlError("connection to the PiSandbox app closed")
            # Replies owed to calls that were interrupted (^C -> KeyboardInterrupt, or a timeout) after
            # sending: drop them so this call does not read an older call's answer.
            while self._stale:
                try:
                    self._replies.get(timeout=2)
                except queue.Empty:
                    pass
                self._stale -= 1
            self._sock.sendall((line + "\n").encode())
            try:
                reply = self._replies.get(timeout=15)
            except BaseException:
                self._stale += 1
                raise
        if reply.startswith("err"):
            raise CtlError(reply[4:])
        return reply[3:]


_conn = None
_conn_lock = threading.Lock()


def connection(client="python"):
    """Process-wide shared connection (created on first use)."""
    global _conn
    with _conn_lock:
        if _conn is None or _conn.closed:
            _conn = Connection(client)
        return _conn


def state():
    """{bcm: dict(mode, pull, level, duty, pressed, color)} - handy for tests and `gpio readall`."""
    out = {}
    for item in connection().call("state").split():
        f = item.split(":")
        b, mode, pull, level, duty, pressed = f[:6]
        out[int(b)] = dict(mode=mode, pull=pull, level=int(level), duty=float(duty), pressed=pressed == "1",
                           color=f[6] if len(f) > 6 else "default")
    return out


LED_COLORS = ("red", "amber", "yellow", "green", "blue", "white", "orange")


def _bcm_of(pin_or_device):
    """BCM number from an int, a gpiozero device (LED, PWMLED, ...) or a gpiozero pin."""
    if isinstance(pin_or_device, int):
        return pin_or_device
    pin = getattr(pin_or_device, "pin", pin_or_device)
    info = getattr(pin, "info", None)
    name = getattr(info, "name", "") or ""
    if name.startswith("GPIO") and name[4:].isdigit():
        return int(name[4:])
    for attr in ("_number", "number"):
        n = getattr(pin, attr, None)
        if isinstance(n, int):
            return n
    raise ValueError(f"cannot tell which GPIO {pin_or_device!r} uses")


def led_color(pin_or_device, color="default"):
    """Colour of the on-screen LED for a pin: led_color(17, "red"), led_color(my_led, "amber"),
    led_color(17, "#ff00ff"). "default" (green) resets it. The colour lasts until the pin is released
    (gpiozero close()/program exit, RPi.GPIO cleanup()). Only changes the picture in the GPIO tab."""
    color = str(color).lower()
    connection().call("color", _bcm_of(pin_or_device), color)
