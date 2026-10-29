#!/usr/bin/env python3
# Mission 4: reaction game. When the LED on GPIO17 lights, hit the GPIO2 button as fast as you can.
# Pressing before the LED lights is a false start: that round does not count and is played again.
from gpiozero import LED, Button
from time import sleep, monotonic
import random

ROUNDS = 5
led, button = LED(17), Button(2)
times, false_starts = [], 0
print(f"Reaction game! Hit the GPIO2 button when GPIO17 lights. {ROUNDS} rounds. Press B or ^C to stop.")
try:
    while len(times) < ROUNDS:
        rnd = len(times) + 1
        if button.is_pressed:              # holding the button down before the game starts
            print("  Let go of the button...")
            button.wait_for_release()
        print(f"Round {rnd}: get ready...")
        # wait a random time - but any press while the LED is still off is a false start
        if button.wait_for_press(timeout=random.uniform(1.5, 4)):
            false_starts += 1
            print("  False start - you pressed before the LED lit! This round doesn't count, try it again.")
            button.wait_for_release()
            sleep(0.5)
            continue
        led.on(); t0 = monotonic()
        button.wait_for_press()
        ms = (monotonic() - t0) * 1000
        led.off()
        times.append(ms)
        print(f"  {ms:.0f} ms")
        if not button.wait_for_release(timeout=1):
            print("  Let go of the button...")
            button.wait_for_release()
    best = min(times)
    print(f"Best: {best:.0f} ms" + ("  - lightning!" if best < 300 else ""))
    print(f"Average: {sum(times) / len(times):.0f} ms" + (f"  (false starts: {false_starts})" if false_starts else ""))
except KeyboardInterrupt:      # ^C (or B in Game mode) stops the program without an error message
    print("\nStopped." + (f" Best so far: {min(times):.0f} ms" if times else ""))
finally:
    led.close()                # LED off, GPIO17 and GPIO2 freed
    button.close()
