# PiSandbox 0.5.0 — tester guide

Thanks for testing! This takes about 20 minutes. You need an Android 8+ 64-bit phone with ~600 MB free.
**Install:** follow [INSTALL.md](INSTALL.md). If you had a 0.2–0.4 test build, uninstall it first (the release
build has a different signature and won't install over it).

PiSandbox collects nothing and sends nothing, so we only learn what you tell us — please fill in the form below.

## Test checklist
1. **First start:** sand animation with "Setting up Linux… N%". How long did it take? Did it reach the terminal?
2. **Terminal:** type `ls`, `python3 --version`, `sudo apt update`. Try the key row: ^C, Tab, arrows (↑ recalls the
   last command), CTRL then `c`.
3. **Lessons:** run "Blink an LED". The GPIO tab should open and an LED should blink. Press **Stop**.
4. **GPIO:** run the button lesson and hold the square button on the header.
5. **Files:** open `examples`, tap a `.py` file, press **Run**.
6. **Browser:** in the terminal run `python3 -m http.server 8000 &`, then in the Browser tab type `8000`.
7. **Settings:** toggle Game mode and LCD tint, change the font size, then close and reopen the app — settings should
   stay. Open **About & licenses** and open one licence.
8. **Lifecycle:** rotate the phone; switch dark mode; use split screen; go Home and come back after a few minutes —
   the same shell (and anything running in it) should still be there. Use **Power off** in the notification.
9. **Start-up again:** reopen the app: a ~2 s animation (tap to skip). With Settings → Accessibility → "Remove
   animations" on, it should be static.
10. **Large text:** set the phone's font size to the largest and look for cut-off buttons or labels.

If the app crashes you'll see a "PiSandbox stopped" screen: tap **Copy details** and paste them into the form.
Nothing is sent automatically.

## Feedback form (copy into a Google Form / Microsoft Form / email — field list)

> **PiSandbox 0.5.0 feedback**
>
> 1. Phone model and Android version: ____  (Settings → About phone)
> 2. Which build did you install? ( ) release ( ) debug — Did you have to uninstall an old version first? ( ) yes ( ) no
> 3. First start: did Linux set up successfully? ( ) yes ( ) no — roughly how many seconds? ____
> 4. Which checklist steps worked? [ ] 1 [ ] 2 [ ] 3 [ ] 4 [ ] 5 [ ] 6 [ ] 7 [ ] 8 [ ] 9 [ ] 10
> 5. What went wrong? Steps to reproduce, what you expected, what happened: ____
> 6. Screenshot(s) (optional): [upload]
> 7. Crash details (from "Copy details", optional): ____
> 8. Anything cut off, overlapping or hard to read (which screen)? ____
> 9. How easy was it for a beginner? (1 = very hard … 5 = very easy)
> 10. What would you like next (e.g. sensors, OLED display, desktop)? ____
> 11. May we contact you about your answers? ( ) no ( ) yes — contact: ____ (optional)
>
> _Privacy: answers are used only to improve PiSandbox and are not shared. Leave out anything personal in crash
> details (they contain the phone model, Android version, free storage and the error, nothing else)._

Note for whoever creates the form: the hosting service (Google/Microsoft/etc.) has its own privacy terms; mention
them in the form description. The app itself never links to or submits the form.
