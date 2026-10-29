#!/usr/bin/env python3
# Bonus: the classic RPi.GPIO API - blink GPIO17 three times, then fade it with PWM.
import RPi.GPIO as GPIO
import time

GPIO.setmode(GPIO.BCM)
GPIO.setup(17, GPIO.OUT)
pwm = None
print("RPi.GPIO: blinking GPIO17 three times, then fading it - press B or ^C to stop")
try:
    for n in range(1, 4):
        print(f"  blink {n}")
        GPIO.output(17, GPIO.HIGH); time.sleep(0.5)
        GPIO.output(17, GPIO.LOW); time.sleep(0.5)
    print("  fading up and down with PWM (100 Hz)")
    pwm = GPIO.PWM(17, 100)
    pwm.start(0)
    for dc in list(range(0, 101, 5)) + list(range(100, -1, -5)):
        pwm.ChangeDutyCycle(dc); time.sleep(0.05)
    print("Done.")
except KeyboardInterrupt:      # ^C (or B in Game mode) stops the program without an error message
    print("\nStopped.")
finally:
    if pwm is not None:
        pwm.stop()
    GPIO.cleanup()             # LED off, GPIO17 freed
