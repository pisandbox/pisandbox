package app.pisandbox

/** The missions shipped in /usr/share/pisandbox/examples (copied to ~/examples at login). */
data class Lesson(val title: String, val file: String, val level: String, val text: String)

object Lessons {
    val all = listOf(
        Lesson("Blink an LED", "blink.py", "Mission 1",
            "Your first program: switch the LED on GPIO17 on and off every second with gpiozero."),
        Lesson("Push button", "button.py", "Mission 2",
            "Hold the button on GPIO2 in the GPIO tab to light the GPIO17 LED. Events, not loops."),
        Lesson("Traffic lights", "traffic_lights.py", "Mission 3",
            "A real traffic light, top to bottom: RED on GPIO17, AMBER (yellow) on GPIO27, GREEN on GPIO22. " +
                "The GPIO tab lights each one in its own colour. Press the GPIO2 button to cross."),
        Lesson("Reaction game", "reaction_game.py", "Mission 4",
            "Hit the GPIO2 button as soon as GPIO17 lights. Five rounds; your best time is shown. " +
                "Press too early and it is a false start: that round is played again."),
        Lesson("Classic RPi.GPIO + PWM", "rpigpio_blink.py", "Bonus",
            "The older RPi.GPIO API used by many tutorials: blink three times, then fade with PWM."),
    )
    fun command(l: Lesson) = "python3 ~/examples/${l.file}"
}
