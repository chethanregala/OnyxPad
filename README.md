# Onyx Pad – phone → Bluetooth HID gamepad

## Get the APK (no Android Studio)
1. Create a GitHub repo, upload/push this folder.
2. Actions tab → "Build APK" → run → download artifact `OnyxPad-apk` → unzip → install `app-debug.apk` on phone (allow unknown sources).

## Or with Android Studio
Open this folder → Run on phone (USB debugging) or Build > Build APK.

## Use
1. Laptop: Settings > Bluetooth > Add device.
2. Phone: open app, allow permissions, tap top pill > "Pair new laptop" (discoverable), pick phone on laptop.
3. Tap top pill > select laptop > status shows CONNECTED.
4. Test on Windows: Win+R > `joy.cpl`.

Requires Android 9+. Some phones don't support the Bluetooth HID-device role.
