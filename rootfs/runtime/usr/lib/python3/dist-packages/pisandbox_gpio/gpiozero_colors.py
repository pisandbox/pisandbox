"""Colour hints for gpiozero devices on the PiSandbox virtual header.

gpiozero has no idea what colour an LED is, so on the on-screen header every LED would be green.
When a composite device names its LEDs after colours - TrafficLights(red=, amber=/yellow=, green=)
and the boards built on it (PiTraffic, PiStop, TrafficHat, ...), or LEDBoard(red=17, green=18) -
those LEDs are drawn in that colour. Installed when the pisandbox pin factory (or the RPi.GPIO shim
under gpiozero) is loaded. It never changes what the program does, and any failure is ignored.
"""
import sys

_installed = False


def _ours(factory):
    """True when the factory drives the PiSandbox header (not MockFactory, a remote pigpio, ...)."""
    try:
        from gpiozero.pins.rpigpio import RPiGPIOFactory
        gpio = sys.modules.get("RPi.GPIO")
        return isinstance(factory, RPiGPIOFactory) and getattr(gpio, "PISANDBOX", False)
    except Exception:
        return False


def color_named_leds(device):
    """Colour the LEDs of a composite device whose names are colours (red, amber, yellow, green, ...)."""
    from gpiozero import LED, PWMLED
    from pisandbox_gpio import LED_COLORS, led_color
    if not _ours(getattr(device, "pin_factory", None)):
        return
    for name, dev in getattr(device, "_named", {}).items():
        if name in LED_COLORS and isinstance(dev, (LED, PWMLED)):
            led_color(dev, name)


def install():
    """Wrap gpiozero's CompositeDevice.__init__ (idempotent). CompositeDevice.__init__ runs after the
    child LEDs exist, so this also covers the very first device, whose construction loaded the factory."""
    global _installed
    if _installed or "gpiozero" not in sys.modules:
        return
    try:
        from gpiozero.devices import CompositeDevice
        orig = CompositeDevice.__init__

        def __init__(self, *args, **kwargs):
            orig(self, *args, **kwargs)
            try:
                color_named_leds(self)
            except Exception:
                pass

        __init__.__wrapped__ = orig
        CompositeDevice.__init__ = __init__
        _installed = True
    except Exception:
        pass
