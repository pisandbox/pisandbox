"""gpiozero pin factory "pisandbox": gpiozero's own RPi.GPIO pin implementation, running on the
PiSandbox RPi.GPIO shim, i.e. on the app's virtual header. Selected by
GPIOZERO_PIN_FACTORY=pisandbox (set for login shells); without it gpiozero's default search still
ends up here via the "rpigpio" factory, because the RPi.GPIO module is the shim."""
from gpiozero.pins.rpigpio import RPiGPIOFactory

from pisandbox_gpio import gpiozero_colors

gpiozero_colors.install()     # TrafficLights & co. light up red / amber / green on the header


class PiSandboxFactory(RPiGPIOFactory):
    def _get_revision(self):
        try:
            return super()._get_revision()      # /proc/device-tree or /proc/cpuinfo (faked by PiSandbox)
        except Exception:
            return 0xc03115                     # Pi 4 Model B 4GB rev 1.5 layout
