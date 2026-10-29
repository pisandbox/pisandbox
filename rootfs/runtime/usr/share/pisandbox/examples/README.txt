PiSandbox missions - virtual GPIO
=================================
The app's GPIO tab shows a 40-pin header. Every pin your program sets as an
OUTPUT becomes an LED that lights up; every INPUT becomes a push button: press and hold it on
the screen. No wiring needed. Pin numbers below are BCM GPIO numbers (as gpiozero uses them).

Mission 1  blink.py            LED on GPIO17 blinks.                   python3 ~/examples/blink.py
Mission 2  button.py           Hold the GPIO2 button -> LED on GPIO17.  python3 ~/examples/button.py
Mission 3  traffic_lights.py   Red GPIO17 (top), amber GPIO27, green GPIO22; GPIO2 crosses.
Mission 4  reaction_game.py    Wait for GPIO17 to light, then hit the GPIO2 button fast!
                               (Pressing too early is a false start: the round is replayed.)
Bonus      rpigpio_blink.py    Same as mission 1 using the classic RPi.GPIO API (with PWM fade).
           gpio readall        WiringPi-style table of all pins.   gpio -g write 17 1
Stop any program with the ^C key or the Stop button on the GPIO tab (B in Game mode),
or tap CTRL then c on the keyboard. Lessons tab -> Run starts a mission for you.

Tip: in a venv, create it with  python3 -m venv --system-site-packages ~/venv  so gpiozero and
RPi.GPIO from the system are visible (same advice as on Pi OS).
