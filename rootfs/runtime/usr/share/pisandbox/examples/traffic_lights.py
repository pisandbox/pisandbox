#!/usr/bin/env python3
# Mission 3: a pedestrian crossing, wired like a real traffic light (top to bottom in the GPIO tab):
#   RED    LED on GPIO17 (top)
#   AMBER  LED on GPIO27 (middle - "yellow")
#   GREEN  LED on GPIO22 (bottom)
# Press the GPIO2 button to request the crossing.
# TrafficLights tells the GPIO tab which LED is which, so they glow red, amber and green.
from gpiozero import TrafficLights, Button
from time import sleep

lights = TrafficLights(red=17, amber=27, green=22)
button = Button(2)
print("Traffic is flowing (GREEN, GPIO22). Press the GPIO2 button to cross. Press B or ^C to stop.")
try:
    while True:
        lights.green.on()
        button.wait_for_press()
        print("Crossing requested... AMBER (GPIO27)")
        lights.green.off(); lights.amber.on(); sleep(2)
        lights.amber.off(); lights.red.on()
        print("RED (GPIO17) - WALK"); sleep(4)
        lights.red.off()
        print("Flashing AMBER - finish crossing")
        for _ in range(3):                 # flashing amber
            lights.amber.on(); sleep(0.4); lights.amber.off(); sleep(0.4)
        print("GREEN again")
except KeyboardInterrupt:      # ^C (or B in Game mode) stops the program without an error message
    print("\nStopped.")
finally:
    lights.close()             # all lights off, GPIO17/27/22 and GPIO2 freed
    button.close()
