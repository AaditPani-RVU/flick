#!/bin/zsh
# Builds "Flick Receiver.app" into mac/build/ using only the Swift command-line tools.
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
APP="build/Flick Receiver.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/release/FlickReceiver "$APP/Contents/MacOS/FlickReceiver"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"
echo "Built $APP"
