#!/usr/bin/env bash
# Takes Google Play screenshots of Flos in a running emulator (see play-screenshots.yml).
# Every step is best effort: a missing button is skipped instead of failing the run.
set -u
OUT=play-screenshots
PKG=cz.flos.pos
mkdir -p "$OUT"
adb install -r app/build/outputs/apk/debug/*.apk

# Clean status bar: 12:00, full battery and signal, no notifications.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

# A slow emulator sometimes shows "System UI isn't responding": hide such dialogs and tap "Wait".
adb shell settings put global hide_error_dialogs 1
sleep 30

# Taps the centre of the first element whose text or description is $1.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || return 0
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if any((v or '').startswith(want) for v in (n.get('text'), n.get('content-desc'))):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; sleep 2; else echo "not found: $1"; return 1; fi
}
dismiss() { for i in 1 2 3; do tap_quiet "Wait"; tap_quiet "Počkat"; done; }
tap_quiet() { tap "$1" >/dev/null || true; }
shot() { dismiss; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "saved $1"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }

# $1 = name prefix, $2 = screen size, $3 = density
take_set() {
  adb shell wm size "$2"
  adb shell wm density "$3"
  adb shell pm clear "$PKG" >/dev/null
  adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT 2>/dev/null
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 15
  dismiss
  tap "Cappuccino"; tap "Cappuccino"; tap "Pizza Margherita"; tap "Croissant"; tap "Pivo 0,5 l"
  shot "$1-1-prodej"
  # Back only closes the payment dialog when it opened; otherwise it would leave the app.
  if tap "Zaplatit"; then shot "$1-2-platba"; back; fi
  tap "Stoly"; shot "$1-3-stoly"
  tap "Účtenky"; shot "$1-4-uctenky"
  tap "Více"; tap "Produkty"; shot "$1-5-produkty"
  tap "Více"; tap "Statistiky"; shot "$1-6-statistiky"
  tap "Více"; tap "Nastavení"; shot "$1-7-nastaveni"
}

# Phone 9:16 and a 10" tablet in landscape (Google Play allows at most a 2:1 ratio).
take_set phone 1080x1920 420
take_set tablet 2560x1600 320
adb shell wm size reset
adb shell wm density reset
ls -la "$OUT"
exit 0
