#!/bin/bash
# Builds RAFIKI Pin for the iPhone simulator. Errors go to build-errors.txt.
cd "$(dirname "$0")/.."
DEV=$(xcrun simctl list devices available | grep -E "^\s+iPhone 1[5-7]( Pro)? \(" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -z "$DEV" ] && DEV=$(xcrun simctl list devices available | grep -E "^\s+iPhone" | head -1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
echo "simulator $DEV"
xcodebuild -project RafikiPin.xcodeproj -scheme RafikiPin -configuration Debug -destination "id=$DEV" -derivedDataPath build/dd CODE_SIGNING_ALLOWED=NO build > build/log.txt 2>&1
code=$?
grep -E "error:|warning: .*(deprecated|never used)|BUILD (SUCCEEDED|FAILED)" build/log.txt | grep -v "^ld: warning" | sort -u > build-errors.txt
tail -3 build/log.txt
echo "exit $code"
echo "$DEV" > build/sim.txt
exit $code
