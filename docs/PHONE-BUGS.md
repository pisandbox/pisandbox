# Phone bugs from S25 testing (v0.3.0, Oct 29 2026)
- B button tap sends ESC (^[) to a running program; users expect B to stop it. Make B tap = Ctrl-C (or make hold-to-Ctrl-C threshold much shorter / show hint). Keep ESC on the key row.
- Key-row labels wrap ("ES/C", "TA/B", "CT/RL"): shrink font or widen buttons.
- Verified working on phone: selfcheck all ok, hostname pisandbox, GPIO panel, blink.py lights GPIO17 LED.
