"""RPi.GPIO-compatible module for PiSandbox: drives the PiSandbox app's on-screen virtual 40-pin
header instead of real hardware (outputs light LEDs, inputs read the on-screen push buttons).
Covers the commonly used API: setmode/getmode/setwarnings/setup/output/input/cleanup/
gpio_function, PWM (start/ChangeDutyCycle/ChangeFrequency/stop), add_event_detect/
remove_event_detect/event_detected/add_event_callback/wait_for_edge."""
import threading as _threading
import time as _time
import warnings as _warnings

from pisandbox_gpio import BOARD_TO_BCM as _B2G, connection as _connection
from pisandbox_gpio import gpiozero_colors as _gpiozero_colors

PISANDBOX = True      # this is the PiSandbox shim, not the hardware library

VERSION = "0.7.1a4"
RPI_REVISION = 3
RPI_INFO = {"P1_REVISION": 3, "REVISION": "c03115", "TYPE": "Pi 4 Model B", "MANUFACTURER": "Sony",
            "PROCESSOR": "BCM2711", "RAM": "4G"}
BOARD, BCM = 10, 11
OUT, IN = 0, 1
LOW, HIGH = 0, 1
PUD_OFF, PUD_DOWN, PUD_UP = 20, 21, 22
RISING, FALLING, BOTH = 31, 32, 33
SERIAL, SPI, I2C, HARD_PWM, UNKNOWN = 40, 41, 42, 43, -1
_EDGE = {RISING: "rising", FALLING: "falling", BOTH: "both"}
_PULL = {PUD_OFF: "off", PUD_UP: "up", PUD_DOWN: "down"}

_mode = None
_warn = True
_dirs = {}      # bcm -> IN/OUT (pins this process set up)
_events = {}    # bcm -> event-detect state
_pwms = set()
_lock = _threading.RLock()


def _c():
    return _connection("RPi.GPIO")


def setwarnings(flag):
    global _warn
    _warn = bool(flag)


def setmode(mode):
    global _mode
    if mode not in (BOARD, BCM):
        raise ValueError("An invalid mode was passed to setmode()")
    if _mode is not None and mode != _mode:
        raise ValueError("A different mode has already been set!")
    _mode = mode


def getmode():
    return _mode


def _bcm(channel):
    if _mode is None:
        raise RuntimeError("Please set pin numbering mode using GPIO.setmode(GPIO.BOARD) or GPIO.setmode(GPIO.BCM)")
    try:
        ch = int(channel)
    except (TypeError, ValueError):
        raise ValueError("Channel must be an integer or list/tuple of integers") from None
    if _mode == BOARD:
        if ch not in _B2G:
            raise ValueError("The channel sent is invalid on a Raspberry Pi")
        return _B2G[ch]
    if not 0 <= ch <= 27:
        raise ValueError("The channel sent is invalid on a Raspberry Pi")
    return ch


def _list(x):
    return list(x) if isinstance(x, (list, tuple)) else [x]


def setup(channel, direction, pull_up_down=PUD_OFF, initial=-1):
    for ch in _list(channel):
        b = _bcm(ch)
        if direction == OUT:
            if pull_up_down != PUD_OFF:
                raise ValueError("pull_up_down parameter is not valid for outputs")
            init = 0 if initial in (-1, None) else int(bool(initial))
            if _warn and _dirs.get(b) is None and _c().call("func", b) != "unused":
                _warnings.warn("This channel is already in use, continuing anyway.  "
                               "Use GPIO.setwarnings(False) to disable warnings.", RuntimeWarning, stacklevel=2)
            _c().call("setup", b, "out", init)
        elif direction == IN:
            if initial not in (-1, None):
                raise ValueError("initial parameter is not valid for inputs")
            if pull_up_down not in _PULL:
                raise ValueError("Invalid value for pull_up_down - should be either PUD_OFF, PUD_UP or PUD_DOWN")
            _c().call("setup", b, "in", _PULL[pull_up_down])
        else:
            raise ValueError("An invalid direction was passed to setup()")
        _dirs[b] = direction


def output(channel, value):
    chans, vals = _list(channel), _list(value)
    if len(vals) == 1 and len(chans) > 1:
        vals = vals * len(chans)
    if len(vals) != len(chans):
        raise RuntimeError("Number of channels != number of values")
    for ch, v in zip(chans, vals):
        b = _bcm(ch)
        if _dirs.get(b) != OUT:
            raise RuntimeError("The GPIO channel has not been set up as an OUTPUT")
        _c().call("write", b, 1 if v else 0)


def input(channel):  # noqa: A001 - RPi.GPIO API name
    b = _bcm(channel)
    if b not in _dirs:
        raise RuntimeError("You must setup() the GPIO channel first")
    return int(_c().call("read", b))


def gpio_function(channel):
    f = _c().call("func", _bcm(channel))
    return OUT if f in ("out", "pwm") else IN


def cleanup(channel=None):
    global _mode
    with _lock:
        bs = list(_dirs) if channel is None else [_bcm(c) for c in _list(channel)]
        for b in bs:
            _stop_events(b)
            if b in _dirs:
                try:
                    _c().call("release", b)
                except Exception:
                    pass
                _dirs.pop(b, None)
                _pwms.discard(b)
        if channel is None:
            _mode = None


# ---- edge detection ----
def _fire(b, value):
    ev = _events.get(b)
    if ev is None:
        return
    e = ev["edge"]
    if (e == RISING and value != 1) or (e == FALLING and value != 0):
        return
    now = _time.monotonic()
    if ev["bounce"] and (now - ev["last"]) * 1000 < ev["bounce"]:
        return
    ev["last"] = now
    ev["flag"] = True
    for cb in list(ev["callbacks"]):
        cb(ev["channel"])


def _start_events(channel, b, edge, bouncetime, callbacks):
    if _dirs.get(b) != IN:
        raise RuntimeError("You must setup() the GPIO channel as an input first")
    if edge not in _EDGE:
        raise ValueError("The edge must be set to RISING, FALLING or BOTH")
    bt = bouncetime if bouncetime and bouncetime > 0 else 0
    _events[b] = dict(edge=edge, bounce=bt, last=0.0, flag=False, channel=channel, callbacks=callbacks)
    _c().on_event(b, lambda v, b=b: _fire(b, v))
    _c().call("watch", b, _EDGE[edge])


def _stop_events(b):
    if _events.pop(b, None) is not None:
        try:
            _c().on_event(b, None)
            _c().call("unwatch", b)
        except Exception:
            pass


def add_event_detect(channel, edge, callback=None, bouncetime=None):
    b = _bcm(channel)
    with _lock:
        if b in _events:
            raise RuntimeError("Conflicting edge detection already enabled for this GPIO channel")
        _start_events(channel, b, edge, bouncetime, [callback] if callback else [])


def remove_event_detect(channel):
    with _lock:
        _stop_events(_bcm(channel))


def event_detected(channel):
    ev = _events.get(_bcm(channel))
    if ev and ev["flag"]:
        ev["flag"] = False
        return True
    return False


def add_event_callback(channel, callback):
    ev = _events.get(_bcm(channel))
    if ev is None:
        raise RuntimeError("Add event detection using add_event_detect first before adding a callback")
    ev["callbacks"].append(callback)


def wait_for_edge(channel, edge, bouncetime=None, timeout=None):
    b = _bcm(channel)
    done = _threading.Event()
    with _lock:
        if b in _events:
            raise RuntimeError("Conflicting edge detection events already exist for this GPIO channel")
        _start_events(channel, b, edge, bouncetime, [lambda ch: done.set()])
    try:
        ok = done.wait(None if timeout is None else timeout / 1000.0)
    finally:
        with _lock:
            _stop_events(b)
    return channel if ok else None


# ---- software PWM ----
class PWM:
    def __init__(self, channel, frequency):
        self._b = _bcm(channel)
        if _dirs.get(self._b) != OUT:
            raise RuntimeError("You must setup() the GPIO channel as an output first")
        if frequency <= 0.0:
            raise ValueError("frequency must be greater than 0.0")
        if self._b in _pwms:
            raise RuntimeError("A PWM object already exists for this GPIO channel")
        _pwms.add(self._b)
        self._freq, self._duty, self._running = float(frequency), 0.0, False

    @staticmethod
    def _dc(dc):
        if not -1e-9 <= dc <= 100.0 + 1e-9:
            raise ValueError("dutycycle must have a value from 0.0 to 100.0")
        return min(100.0, max(0.0, float(dc)))

    def start(self, dutycycle):
        self._duty = self._dc(dutycycle)
        _c().call("pwm", self._b, self._freq, self._duty)
        self._running = True

    def ChangeDutyCycle(self, dutycycle):
        self._duty = self._dc(dutycycle)
        if self._running:
            _c().call("pwm", self._b, self._freq, self._duty)

    def ChangeFrequency(self, frequency):
        if frequency <= 0.0:
            raise ValueError("frequency must be greater than 0.0")
        self._freq = float(frequency)
        if self._running:
            _c().call("pwm", self._b, self._freq, self._duty)

    def stop(self):
        if self._running:
            self._running = False
            try:
                _c().call("pwmstop", self._b)
            except Exception:
                pass
        _pwms.discard(self._b)

    def __del__(self):
        try:
            self.stop()
        except Exception:
            pass


_gpiozero_colors.install()   # when gpiozero loaded us (rpigpio factory): colour TrafficLights LEDs
