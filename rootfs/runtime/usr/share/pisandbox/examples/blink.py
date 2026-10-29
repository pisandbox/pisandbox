#!/usr/bin/env python3
# Mission 1: blink an LED. Watch GPIO17 on the GPIO panel (GPIO tab).
from gpiozero import LED
from time import sleep

led = LED(17)
print("Blinking GPIO17 - press B or ^C to stop")
try:
    while True:
        led.on()
        sleep(1)
        led.off()
        sleep(1)
except KeyboardInterrupt:      # ^C (or B in Game mode) stops the program without an error message
    print("\nStopped.")
finally:
    led.close()                # switch the LED off and free GPIO17
