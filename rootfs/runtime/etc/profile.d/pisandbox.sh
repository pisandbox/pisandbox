# PiSandbox session setup (login shells). Installed/updated by the app on every app update.
export GPIOZERO_PIN_FACTORY="${GPIOZERO_PIN_FACTORY:-pisandbox}"   # gpiozero -> on-screen header
# Beginner examples/missions (~/examples). Synced once per app install/update:
#  - missing files are copied;
#  - files you never changed (their sha256 matches a version we shipped) are updated to the new version;
#  - files you edited are kept as they are (the new version stays in /usr/share/pisandbox/examples).
if [ "$(id -u)" != 0 ] && [ -d /usr/share/pisandbox/examples ] && [ -w "$HOME" ]; then
  _src=/usr/share/pisandbox/examples
  _ver="$(cat /etc/pisandbox/runtime-stamp 2>/dev/null) $(cksum < "$_src/.shipped-hashes" 2>/dev/null)"
  if [ "$(cat "$HOME/examples/.pisandbox-synced" 2>/dev/null)" != "$_ver" ] && mkdir -p "$HOME/examples" 2>/dev/null; then
    _kept=""
    for _f in "$_src"/*; do
      [ -f "$_f" ] || continue
      _n=${_f##*/}; _d="$HOME/examples/$_n"
      if [ ! -e "$_d" ] && [ ! -L "$_d" ]; then
        cp "$_f" "$_d" 2>/dev/null
      elif [ -f "$_d" ] && [ ! -L "$_d" ] && ! cmp -s "$_f" "$_d"; then
        _h=$(sha256sum < "$_d" | cut -d' ' -f1)
        if grep -qxF "$_h  $_n" "$_src/.shipped-hashes" 2>/dev/null; then cp "$_f" "$_d" 2>/dev/null
        else _kept="$_kept $_n"; fi
      fi
    done
    printf '%s\n' "$_ver" > "$HOME/examples/.pisandbox-synced" 2>/dev/null
    [ -n "$_kept" ] && [ -n "${PS1:-}" ] && printf 'Examples updated. Kept your edited:%s (new versions in %s)\n' "$_kept" "$_src"
    unset _kept _f _n _d _h
  fi
  unset _src _ver
fi
# Banner (interactive login shells only, once per session)
if [ -n "${PS1:-}" ] && [ -z "${PISANDBOX_BANNER_SHOWN:-}" ]; then
  export PISANDBOX_BANNER_SHOWN=1
  . /etc/os-release 2>/dev/null
  printf '\nPiSandbox %s - %s (%s) in a PRoot sandbox on Android\n' \
    "$(cat /etc/pisandbox/version 2>/dev/null)" "${PRETTY_NAME:-Linux}" "$(dpkg --print-architecture 2>/dev/null)"
  printf 'User "%s" has passwordless sudo.  Try:  sudo apt update   python3   pisandbox-fetch\n' "$(id -un)"
  printf 'GPIO: open the Lessons or GPIO tab, or:  python3 ~/examples/blink.py   (stop: ^C key, or B in Game mode)\n'
  printf 'Web:  browse <url>   e.g.  python3 -m http.server 8000 &  then  browse 8000\n\n'
  # quick self-check of the board overlays (full report: pisandbox-selfcheck)
  _w=""
  [ "$(hostname 2>/dev/null)" = pisandbox ] || _w="$_w hostname"
  head -c1 /proc/device-tree/model >/dev/null 2>&1 || _w="$_w device-tree"
  [ -n "$_w" ] && printf '\033[1;33mWarning: board emulation incomplete (%s ). Run pisandbox-selfcheck and send the output to the developer.\033[0m\n\n' "$_w"
  unset _w
fi
