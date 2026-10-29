#!/usr/bin/env python3
"""Reference/mock implementation of the PiSandbox control-socket protocol (mirror of
app/src/main/java/app/pisandbox/GpioCore.kt), for testing the guest-side shims without the app.
  gpio-mock-server.py unix:@app.pisandbox.ctl | tcp:127.0.0.1:PORT   [--browse-log FILE]"""
import socket, sys, threading

class Pin:
    def __init__(s, b): s.bcm, s.mode, s.pull, s.level, s.duty, s.freq, s.pressed, s.color = b, "unused", "off", 0, 0.0, 0.0, False, "default"

pins = [Pin(i) for i in range(28)]
NAMED = dict(red="red", amber="amber", yellow="amber", green="green", blue="blue", white="white", orange="orange")

def parse_color(v):
    v = v.lower()
    if v in ("default", "none"): return "default"
    if v in NAMED: return NAMED[v]
    assert len(v) == 7 and v[0] == "#" and all(ch in "0123456789abcdef" for ch in v[1:]), \
        "colour must be red|amber|yellow|green|blue|white|orange|#rrggbb|default"
    return {"#ff3b30": "red", "#ffb000": "amber", "#43e07a": "green", "#4da6ff": "blue", "#ffffff": "white", "#ff7a1a": "orange"}.get(v, v)
lock = threading.RLock(); sessions = []
browse_log = sys.argv[sys.argv.index("--browse-log") + 1] if "--browse-log" in sys.argv else None

def value(p):
    if p.mode == "out": return p.level
    if p.mode == "pwm": return 1 if p.duty >= 50 else 0
    return (0 if p.pull == "up" else 1) if p.pressed else (1 if p.pull == "up" else 0)

class Session:
    def __init__(s, conn): s.conn, s.watches, s.wl = conn, {}, threading.Lock()
    def send(s, line):
        with s.wl:
            try: s.conn.sendall((line + "\n").encode())
            except OSError: pass

def mutate(fn):
    with lock:
        before = [value(p) for p in pins]; r = fn()
        ev = [(p.bcm, value(p)) for p in pins if value(p) != before[p.bcm]]
        for b, v in ev:
            for s in list(sessions):
                e = s.watches.get(b)
                if e and (e == "both" or (e == "rising" and v == 1) or (e == "falling" and v == 0)): s.send(f"ev {b} {v}")
    return r

def handle(s, line):
    a = line.split()
    def pin(i=1):
        n = int(a[i]);  assert 0 <= n <= 27, f"invalid GPIO {n} (BCM 0-27)"; return pins[n]
    def bit(i):
        assert a[i] in ("0", "1"), "value must be 0 or 1"; return int(a[i])
    try:
        c = a[0]
        if c == "hello": return "ok pisandbox 1 c03115"
        if c == "setup":
            p = pin()
            def f():
                if a[2] == "out": p.mode, p.pull, p.level, p.duty = "out", "off", bit(3), 0.0
                elif a[2] == "in":
                    assert a[3] in ("off", "up", "down"), "pull must be off|up|down"; p.mode, p.pull, p.level, p.duty = "in", a[3], 0, 0.0
                else: raise AssertionError("direction must be in|out")
            mutate(f); return "ok"
        if c == "release":
            p = pin(); mutate(lambda: [setattr(p, k, v) for k, v in dict(mode="unused", pull="off", level=0, duty=0.0, freq=0.0, color="default").items()])
            s.watches.pop(p.bcm, None); return "ok"
        if c == "write":
            p = pin(); v = bit(2)
            def f():
                assert p.mode == "out", f"GPIO {p.bcm} is not set up as an output"; p.level = v
            mutate(f); return "ok"
        if c == "read": return f"ok {value(pin())}"
        if c == "func": return f"ok {pin().mode}"
        if c == "pwm":
            p = pin(); f_, d = float(a[2]), float(a[3]); assert f_ > 0, "frequency must be > 0"; assert 0 <= d <= 100, "duty must be 0-100"
            def f():
                assert p.mode in ("out", "pwm"), f"GPIO {p.bcm} is not set up as an output"; p.mode, p.freq, p.duty = "pwm", f_, d
            mutate(f); return "ok"
        if c == "pwmstop":
            p = pin()
            def f():
                if p.mode == "pwm": p.mode, p.level, p.duty = "out", 0, 0.0
            mutate(f); return "ok"
        if c == "watch":
            p = pin(); e = a[2] if len(a) > 2 else "both"; assert e in ("rising", "falling", "both"), "edge must be rising|falling|both"
            s.watches[p.bcm] = e; return "ok"
        if c == "unwatch": s.watches.pop(pin().bcm, None); return "ok"
        if c == "color":
            p = pin()
            if len(a) < 3: return f"ok {p.color}"
            col = parse_color(a[2]); mutate(lambda: setattr(p, "color", col)); return "ok"
        if c == "press":
            p = pin(); v = bit(2); mutate(lambda: setattr(p, "pressed", v == 1)); return "ok"
        if c == "state":
            return "ok " + " ".join(f"{p.bcm}:{p.mode}:{p.pull}:{p.level}:{p.duty}:{int(p.pressed)}:{p.color}" for p in pins)
        if c == "browse":
            url = line.split(None, 1)[1].strip() if len(a) > 1 else ""
            assert url.startswith(("http://", "https://")), "only http:// and https:// URLs"
            if browse_log: open(browse_log, "a").write(url + "\n")
            return "ok"
        return f"err unknown command '{c}'"
    except (AssertionError, ValueError, IndexError) as e:
        return f"err {e}"

def client(conn):
    s = Session(conn); sessions.append(s)
    try:
        for line in conn.makefile("r", encoding="utf-8"):
            if line.strip(): s.send(handle(s, line.strip()))
    finally:
        sessions.remove(s); conn.close()

def main():
    addr = sys.argv[1]
    if addr.startswith("tcp:"):
        h, p = addr[4:].rsplit(":", 1); srv = socket.create_server((h, int(p)))
    else:
        path = addr[5:]; srv = socket.socket(socket.AF_UNIX); srv.bind("\0" + path[1:] if path.startswith("@") else path); srv.listen()
    print("mock control server on", addr, flush=True)
    while True:
        c, _ = srv.accept(); threading.Thread(target=client, args=(c,), daemon=True).start()

if __name__ == "__main__":
    main()
