# Flick

Flick your Android phone toward your Mac and the photo, file or copied text lands on the Mac.

- **`android/`** — the phone app. Pick photos or files, share into it from any app, or copy text and open it. Then flick your wrist toward the Mac, or swipe the card up.
- **`mac/`** — Flick Receiver, a menu bar app. Files arrive as a card that flies up from the bottom of the screen and are saved to Downloads; text goes to the clipboard.

The two talk directly over your Wi‑Fi. The phone finds the Mac with Bonjour (`_flickdrop._tcp`), and the first send asks you to click Allow on the Mac.

## Build

**Mac** (needs the Swift command-line tools, no Xcode):

```sh
cd mac
./build.sh
open "build/Flick Receiver.app"
```

**Android** (needs Gradle and the Android SDK, platform 36):

```sh
cd android
echo "sdk.dir=/path/to/android/sdk" > local.properties
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Limits

- Both devices must be on the same Wi‑Fi network. If the phone cannot find the Mac, tap the status line at the top of the app and enter the address shown in the Flick menu on the Mac.
- Transfers use plain HTTP on the local network and are not encrypted. Use it on networks you trust.
- If no receiver is found, a flick opens the system share sheet so you can send with Quick Share instead.
