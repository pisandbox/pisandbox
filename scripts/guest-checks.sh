# Runs inside the guest as user pi (sourced by host-verify.sh via bash -il). Prints PASS/FAIL lines.
set -u; fails=0
ok()  { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; fails=$((fails+1)); }
t()   { local d=$1; shift; if out=$("$@" 2>&1); then ok "$d"; else bad "$d"; echo "$out" | tail -5; fi; }
echo "--- identity"; id; uname -a; hostname; echo "PS1 (expanded): ${PS1@P}"
[ "$(id -un)" = pi ] && ok "running as pi" || bad "running as pi"
[ "$(hostname)" = pisandbox ] && ok "hostname pisandbox" || bad "hostname"
[ "$(cat /etc/hostname)" = pisandbox ] && [ "$(uname -n)" = pisandbox ] && ok "/etc/hostname + uname -n match" || bad "/etc/hostname / uname -n"
plain=$(printf '%s' "${PS1@P}" | sed -e 's/\x1b\][^\x07]*\x07//g' -e 's/\x1b\[[0-9;]*m//g')
[ "$plain" = 'pi@pisandbox:~ $ ' ] && ok "Pi-OS-style prompt '$plain'" || bad "prompt '$plain'"
echo "--- board"; tr -d '\0' < /proc/device-tree/model; echo; tail -5 /proc/cpuinfo; cat /proc/version
tr -d '\0' < /proc/device-tree/model | grep -q '^Raspberry Pi 4 Model B' && ok "device-tree model" || bad "device-tree model"
tr -d '\0' < /sys/firmware/devicetree/base/model | grep -q '^Raspberry Pi 4 Model B' && ok "/sys/firmware/devicetree/base/model" || bad "/sys/firmware/devicetree/base/model"
tr '\0' ' ' < /proc/device-tree/compatible | grep -q 'raspberrypi,4-model-b' && ok "device-tree compatible" || bad "device-tree compatible"
CI=/proc/cpuinfo
if [ -n "${PISANDBOX_HOST_QEMU:-}" ] && ! grep -q '^Revision' /proc/cpuinfo; then
  # qemu-user answers /proc/cpuinfo itself (before PRoot's bind is consulted) -- host-test-only
  # artefact; there is no qemu on the phone. Check the generated file that is bound there instead.
  echo "NOTE: qemu-user intercepts /proc/cpuinfo on this host; checking the bound source file"; CI=/etc/pisandbox/generated/cpuinfo
fi
grep -q '^Revision.*c03115' $CI && ok "cpuinfo Revision" || bad "cpuinfo Revision"
grep -q '^Model.*Raspberry Pi 4' $CI && ok "cpuinfo Model" || bad "cpuinfo Model"
python3 - <<'PY' && ok "Pi-detection snippet (device-tree model)" || bad "Pi detection"
import io
m = io.open('/proc/device-tree/model').read()
assert 'raspberry pi' in m.lower(), m
PY
[ "$(od -An -tx1 /proc/device-tree/system/linux,revision | tr -d ' ')" = 00c03115 ] && ok "linux,revision BE" || bad "linux,revision"
[ -f /boot/firmware/config.txt ] && [ -f /boot/firmware/cmdline.txt ] && ok "/boot/firmware stubs" || bad "/boot/firmware"
uname -r | grep -q 'rpt-rpi-v8$' && ok "uname -r suffix" || bad "uname -r"
cat /proc/loadavg /proc/uptime >/dev/null && head -1 /proc/stat && ok "/proc/{loadavg,uptime,stat} readable" || bad "/proc fallbacks"
echo "--- sudo"
o=$(sudo id 2>&1); echo "$o"
[ "$(sudo id -u)" = 0 ] && ok "sudo -> root" || bad "sudo"
echo "$o" | grep -qi audit && bad "audit noise" || ok "no audit noise"
echo "--- apt"
t "sudo apt-get update" sudo apt-get update
t "sudo apt-get install cowsay (small pkg)" sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends cowsay
/usr/games/cowsay moo >/dev/null 2>&1 && ok "installed program runs" || bad "cowsay run"
echo "--- python"
[ "$(python3 -c 'print(1)')" = 1 ] && ok "python3 -c 'print(1)' ($(python3 -V))" || bad "python3"
python3 -m pip install --user six >/dev/null 2>&1 && bad "PEP 668 not enforced?" || ok "pip refuses system install (PEP 668, like Pi OS)"
t "python3 -m venv ~/venv" python3 -m venv ~/venv
t "pip install in venv (six)" ~/venv/bin/pip install -q six
[ "$(~/venv/bin/python -c 'import six; print(six.__version__ > "1")')" = True ] && ok "import from venv" || bad "venv import"
t "pip install binary wheel in venv (pyyaml)" ~/venv/bin/pip install -q pyyaml
echo "--- self-check"
pisandbox-selfcheck | grep -v 'GPIO panel' ; ok "pisandbox-selfcheck ran"
echo "--- GPIO (guest python $(python3 -V 2>&1 | cut -d' ' -f2) -> abstract socket -> mock server)"
t "python imports: RPi.GPIO, gpiozero, pisandbox_gpio" python3 -c 'import RPi.GPIO, gpiozero, pisandbox_gpio, colorzero; import importlib.metadata as m; print(m.version("gpiozero"))'
[ -f ~/examples/blink.py ] && [ -f ~/examples/button.py ] && [ -f ~/examples/reaction_game.py ] && ok "~/examples copied at login" || bad "~/examples"
echo "--- examples sync on app update"
S=/usr/share/pisandbox/examples
echo "# my change" >> ~/examples/blink.py                        # user-edited -> must be kept
[ -f /tmp/old-button.py ] && cp /tmp/old-button.py ~/examples/button.py   # an older shipped version -> must be updated
rm -f ~/examples/traffic_lights.py                                # deleted -> restored on update
echo "my own file" > ~/examples/mine.py                           # user's own file -> untouched
echo "stale" > ~/examples/.pisandbox-synced                       # what an app update does: new runtime stamp
msg=$(bash -lic 'true' 2>&1)
grep -q '# my change' ~/examples/blink.py && ok "edited example kept (blink.py)" || bad "edited example overwritten"
if [ -f /tmp/old-button.py ]; then cmp -s $S/button.py ~/examples/button.py && ok "unmodified old example updated (button.py)" || bad "old button.py not updated"; fi
cmp -s $S/traffic_lights.py ~/examples/traffic_lights.py && ok "missing example restored" || bad "traffic_lights.py not restored"
[ "$(cat ~/examples/mine.py)" = "my own file" ] && ok "user's own file untouched" || bad "mine.py changed"
echo "$msg" | grep -q 'Kept your edited: blink.py' && ok "login notes the kept file" || bad "no kept-file note: $msg"
before=$(stat -c %Y ~/examples/.pisandbox-synced); sleep 1; bash -lc true; [ "$(stat -c %Y ~/examples/.pisandbox-synced)" = "$before" ] && ok "sync runs once per update (not every login)" || bad "sync re-ran"
rm -f ~/examples/mine.py
if out=$(python3 /tmp/gpio-conformance.py 2>&1); then ok "GPIO conformance (RPi.GPIO + gpiozero + gpio CLI + browse)"; else bad "GPIO conformance"; fi
echo "$out" | grep -E 'FAIL|conformance'
t "sudo keeps GPIOZERO_PIN_FACTORY" sh -c '[ "$(sudo sh -c "echo \$GPIOZERO_PIN_FACTORY")" = pisandbox ]'
timeout 60 python3 - <<'PY' && ok "examples/blink.py drives GPIO17 (2 blinks observed)" || bad "blink.py"
import subprocess, time, pisandbox_gpio as pg
p = subprocess.Popen(["python3", "/home/pi/examples/blink.py"], stdout=subprocess.DEVNULL)
seen, t0 = [], time.monotonic()
while time.monotonic() - t0 < 30 and seen.count(1) < 2:
    v = pg.state()[17]["level"]
    if not seen or seen[-1] != v: seen.append(v)
    time.sleep(0.05)
p.terminate(); p.wait()
assert seen.count(1) >= 2, seen
PY
timeout 150 python3 - <<'PY' && ok "all examples stop cleanly on ^C: 'Stopped.', no traceback, exit 0, GPIO freed" || bad "examples ^C"
import subprocess, signal, time, pisandbox_gpio as pg
for ex in ["blink.py", "button.py", "traffic_lights.py", "reaction_game.py", "rpigpio_blink.py"]:
    p = subprocess.Popen(["python3", "-u", "/usr/share/pisandbox/examples/" + ex],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    first = p.stdout.readline()          # start message printed before any GPIO wait
    time.sleep(1.2)
    p.send_signal(signal.SIGINT)
    out = first + p.communicate(timeout=20)[0]
    assert p.returncode == 0 and "Stopped." in out and "Traceback" not in out, (ex, p.returncode, out)
    st = pg.state()
    assert all(st[b]["mode"] == "unused" for b in (2, 17, 22, 27)), (ex, {b: st[b] for b in (2, 17, 22, 27)})
PY
t "pisandbox-fetch" sh -c 'pisandbox-fetch | grep -q "Raspberry Pi 4 Model B"'
t "gpio readall" sh -c 'gpio readall | grep -q "GPIO.17"'
echo "--- summary: $fails failure(s)"; exit $fails
