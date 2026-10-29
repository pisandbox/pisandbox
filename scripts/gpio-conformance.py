#!/usr/bin/env python3
"""End-to-end test of the guest-side GPIO stack (RPi.GPIO shim, gpiozero via the 'pisandbox' pin
factory, `gpio`/`browse` commands) against a control server: the mock (gpio-mock-server.py) or
the app's real GpioCore (JVM unit test). Prints PASS/FAIL lines, exits with the failure count."""
import os, subprocess, sys, threading, time
fails = 0
def check(name, cond, detail=""):
    global fails
    print(("PASS: " if cond else "FAIL: ") + name + ("" if cond else f"  {detail}"), flush=True)
    fails += 0 if cond else 1

import pisandbox_gpio as pg
ctl = pg.Connection("conformance")
st = pg.state
def press(b, down): ctl.call("press", b, 1 if down else 0)
def wait(cond, t=2.0):
    end = time.monotonic() + t
    while time.monotonic() < end:
        if cond(): return True
        time.sleep(0.01)
    return False

# ---- RPi.GPIO ----
import RPi.GPIO as GPIO
check("RPi.GPIO import + constants", GPIO.BCM == 11 and GPIO.OUT == 0 and GPIO.PUD_UP == 22)
try: GPIO.setup(17, GPIO.OUT); check("setup without setmode raises", False)
except RuntimeError: check("setup without setmode raises", True)
GPIO.setmode(GPIO.BCM); GPIO.setwarnings(False)
GPIO.setup(17, GPIO.OUT)
GPIO.output(17, GPIO.HIGH); check("output HIGH -> LED on", st()[17]["level"] == 1 and st()[17]["mode"] == "out")
check("input() on output returns latch", GPIO.input(17) == 1)
GPIO.output(17, 0); check("output LOW -> LED off", st()[17]["level"] == 0)
GPIO.setup(4, GPIO.IN, pull_up_down=GPIO.PUD_UP)
check("input pull-up reads 1", GPIO.input(4) == 1)
press(4, True); check("pressed button reads 0 (to GND)", GPIO.input(4) == 0); press(4, False)
GPIO.setup(5, GPIO.IN, pull_up_down=GPIO.PUD_DOWN)
press(5, True); check("pull-down + pressed reads 1", GPIO.input(5) == 1); press(5, False)
seen = []
GPIO.add_event_detect(4, GPIO.FALLING, callback=lambda ch: seen.append(ch))
press(4, True); press(4, False); press(4, True); press(4, False)
check("add_event_detect FALLING callbacks", wait(lambda: len(seen) == 2) and seen == [4, 4], seen)
check("event_detected()", GPIO.event_detected(4) and not GPIO.event_detected(4))
GPIO.remove_event_detect(4)
threading.Timer(0.2, lambda: press(5, True)).start()
check("wait_for_edge RISING", GPIO.wait_for_edge(5, GPIO.RISING, timeout=3000) == 5); press(5, False)
check("wait_for_edge timeout -> None", GPIO.wait_for_edge(5, GPIO.RISING, timeout=100) is None)
pwm = GPIO.PWM(17, 100); pwm.start(25)
check("PWM start 25%", st()[17]["mode"] == "pwm" and st()[17]["duty"] == 25.0)
pwm.ChangeDutyCycle(80); check("PWM ChangeDutyCycle", st()[17]["duty"] == 80.0)
pwm.stop(); check("PWM stop", st()[17]["mode"] == "out")
GPIO.cleanup()
check("cleanup releases pins", st()[17]["mode"] == "unused" and st()[4]["mode"] == "unused")
GPIO.setmode(GPIO.BOARD); GPIO.setup(11, GPIO.OUT, initial=GPIO.HIGH)
check("BOARD numbering: pin 11 = GPIO17", st()[17]["level"] == 1); GPIO.cleanup()

# ---- gpiozero ----
os.environ["GPIOZERO_PIN_FACTORY"] = "pisandbox"
from gpiozero import Device, LED, PWMLED, Button, TrafficLights
check("gpiozero uses the pisandbox pin factory", type(Device._default_pin_factory()).__name__ == "PiSandboxFactory")
led = LED(17)
check("gpiozero board info = Pi 4B", "4B" in Device.pin_factory.board_info.model, Device.pin_factory.board_info.model)
led.on(); check("LED.on", st()[17]["level"] == 1)
led.off(); check("LED.off", st()[17]["level"] == 0)
levels = []
led.blink(on_time=0.05, off_time=0.05, n=3, background=True)
end = time.monotonic() + 1.0
while time.monotonic() < end: levels.append(st()[17]["level"]); time.sleep(0.01)
ons = sum(1 for a, b in zip(levels, levels[1:]) if a == 0 and b == 1) + (levels[0] == 1)
check("LED.blink(n=3) blinks 3 times", ons == 3 and levels[-1] == 0, f"{ons} rising edges")
led.close(); check("LED.close releases pin", st()[17]["mode"] == "unused")
pl = PWMLED(18); pl.value = 0.5; check("PWMLED 50%", st()[18]["mode"] == "pwm" and abs(st()[18]["duty"] - 50) < 1e-6)
pl.close()
btn = Button(2); evs = []
btn.when_pressed = lambda: evs.append("p"); btn.when_released = lambda: evs.append("r")
check("Button idle not pressed", not btn.is_pressed)
press(2, True); check("Button.is_pressed", wait(lambda: btn.is_pressed))
wait(lambda: evs == ["p"])   # a real finger press lasts >50 ms; let the callback run first
press(2, False)
check("Button when_pressed/when_released", wait(lambda: evs == ["p", "r"]), evs)
threading.Timer(0.2, lambda: press(2, True)).start()
check("Button.wait_for_press", btn.wait_for_press(timeout=3)); press(2, False); btn.close()
tl = TrafficLights(22, 27, 17); tl.value = (1, 0, 1)
s = st(); check("TrafficLights", (s[22]["level"], s[27]["level"], s[17]["level"]) == (1, 0, 1)); tl.close()
# LED colours on the virtual header (0.5.1)
led = LED(5); check("plain LED keeps the default colour", st()[5]["color"] == "default"); led.close()
tl = TrafficLights(red=17, amber=27, green=22)
s = st(); check("TrafficLights colours red/amber/green", (s[17]["color"], s[27]["color"], s[22]["color"]) == ("red", "amber", "green"), s[17])
tl.close(); s = st()
check("close() resets the colours", all(s[b]["color"] == "default" and s[b]["mode"] == "unused" for b in (17, 27, 22)))
tl = TrafficLights(9, 10, 11)           # positional red, amber, green
s = st(); check("TrafficLights positional pins coloured", (s[9]["color"], s[10]["color"], s[11]["color"]) == ("red", "amber", "green")); tl.close()
from gpiozero import LEDBoard
tl = TrafficLights(red=13, yellow=19, green=26, pwm=True)
s = st(); check("TrafficLights(yellow=, pwm=True) colours", (s[13]["color"], s[19]["color"], s[26]["color"]) == ("red", "amber", "green"), s[19]); tl.close()
lb = LEDBoard(red=20, blue=21, other=16)
s = st(); check("LEDBoard named colours", (s[20]["color"], s[21]["color"], s[16]["color"]) == ("red", "blue", "default")); lb.close()
led = LED(12); pg.led_color(led, "#FF00FF"); check("led_color(device, #rrggbb)", st()[12]["color"] == "#ff00ff")
pg.led_color(12, "yellow"); check("led_color(bcm, yellow) = amber", st()[12]["color"] == "amber")
pg.led_color(12, "default"); check("led_color default", st()[12]["color"] == "default")
try: pg.led_color(12, "purple-ish"); check("bad colour raises", False)
except pg.CtlError: check("bad colour raises", True)
led.close()

# ---- commands ----
ctl.call("release", 21)
r = subprocess.run(["gpio", "-g", "write", "21", "1"], capture_output=True, text=True)
r2 = subprocess.run(["gpio", "-g", "mode", "21", "out"], capture_output=True, text=True)
subprocess.run(["gpio", "-g", "write", "21", "1"], capture_output=True, text=True)
check("gpio CLI mode/write (state persists after exit)", r.returncode != 0 and r2.returncode == 0 and st()[21]["level"] == 1, r.stderr + r2.stderr)
r = subprocess.run(["gpio", "readall"], capture_output=True, text=True)
check("gpio readall", r.returncode == 0 and "39 || 40" in r.stdout and "GPIO.21" in r.stdout, r.stderr)
subprocess.run(["gpio", "-g", "color", "21", "red"], capture_output=True, text=True)
r = subprocess.run(["gpio", "-g", "color", "21"], capture_output=True, text=True)
r2 = subprocess.run(["gpio", "-1", "colour", "40", "#00ff00"], capture_output=True, text=True)   # phys 40 = GPIO21
check("gpio CLI color", r.stdout.strip() == "red" and r2.returncode == 0 and st()[21]["color"] == "#00ff00", r.stdout + r.stderr + r2.stderr)
ctl.call("release", 21); check("release resets colour", st()[21]["color"] == "default")
r = subprocess.run(["browse", "8000"], capture_output=True, text=True)
check("browse 8000 -> http://localhost:8000", r.returncode == 0, r.stdout + r.stderr)
print(f"--- conformance: {fails} failure(s)")
sys.exit(fails)
