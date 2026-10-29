#!/usr/bin/env python3
# Mission 2: hold the on-screen button on GPIO2 and the LED on GPIO17 lights up.
from gpiozero import LED, Button
from signal import pause

led = LED(17)
button = Button(2)
button.when_pressed = lambda: (led.on(), print("pressed"))
button.when_released = lambda: (led.off(), print("released"))
print("Hold the GPIO2 button on the panel. Press B or ^C to stop.")
pause()
