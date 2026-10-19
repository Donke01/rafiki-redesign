#!/bin/bash
# Opens the app on the simulator in demo mode and takes screenshots of each screen.
cd "$(dirname "$0")/.."
DEV=$(cat build/sim.txt)
APP=build/dd/Build/Products/Debug-iphonesimulator/RafikiPin.app
xcrun simctl boot "$DEV" 2>/dev/null; xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 2>/dev/null
xcrun simctl install "$DEV" "$APP"
xcrun simctl privacy "$DEV" grant location org.rafikioutreach.pin 2>/dev/null
xcrun simctl location "$DEV" set 0.5150,35.2700 2>/dev/null
mkdir -p shots
shot() { name=$1; shift
  xcrun simctl terminate "$DEV" org.rafikioutreach.pin 2>/dev/null
  xcrun simctl launch "$DEV" org.rafikioutreach.pin -demo "$@" >/dev/null
  sleep ${WAIT:-4}
  xcrun simctl io "$DEV" screenshot "shots/$name.png" >/dev/null 2>&1 && echo "shot $name"
}
while read -r line; do [ -z "$line" ] && continue; eval shot $line; done < "${1:-tools/shots.txt}"
