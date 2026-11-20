# Onyx Pad – phone → virtual Xbox 360 controller over Wi-Fi

Use an Android phone as a gamepad. The phone sends its pad state over **Wi-Fi/UDP**, and
a small Python receiver on Windows turns it into a **virtual Xbox 360 controller** with
rumble support. No Bluetooth HID needed (works on phones without the HID-device role,
e.g. Moto G45).

```
[Onyx Pad app] --UDP 7777--> [laptop/receiver.py + ViGEmBus] --> Xbox 360 pad in Windows
                <--rumble----
```

## Get the APK (no Android Studio)
1. This repo builds automatically on every push: **Actions** tab → *Build APK* → latest run.
2. Download the artifact `OnyxPad-apk`, unzip it and install `app-debug.apk` on the phone
   (allow installation from unknown sources).

## Or with Android Studio
Open this folder → Run on the phone (USB debugging) or *Build > Build APK(s)*.

## Laptop setup
Everything is in [`laptop/`](laptop/):

1. Double-click **`laptop/run.bat`** (installs `vgamepad`, then runs `receiver.py`).
2. If `vgamepad` cannot create the controller, install **ViGEmBus**
   (<https://github.com/nefarius/ViGEmBus/releases>) and reboot.
3. Allow **Python** through Windows Firewall on **Private networks** (UDP **7777**).
4. Put the laptop on the **same Wi-Fi** as the phone, or connect the laptop to the
   **phone's hotspot**. The console prints the laptop's LAN IP.

See [`laptop/README.md`](laptop/README.md) for details.

## Use
1. Start `receiver.py` on the laptop.
2. Open the app on the phone. The top pill shows:
   - `SEARCHING…` – broadcasting for a receiver,
   - `CONNECTED · <hostname or IP>` – locked on and streaming,
   - `LOST – RECONNECTING…` – no reply from the laptop for 3 s (reconnects automatically).
3. If discovery is blocked, **tap the pill** and type the laptop IP (it is saved), then
   press **Save** or **Retry**. Long-press the pill to see a network log.
4. Test on Windows with `Win+R` → `joy.cpl`, or <https://gamepad-tester.com>.
5. Games can drive phone rumble; the app vibrates while the game reports rumble.

The screen stays on while the app is open. Requires Android 9+ (minSdk 28).

## Protocol (for reference)
- Phone → laptop: `0x01` + 9-byte report, on change **and** at 20 Hz.
  Report: `[btnLo, btnHi(bits 8-11), hat(0 none, 1 N … 8 NW), lx, ly, rx, ry, L2, R2]`.
  Button bits: 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 L2, 7 R2, 8 Back, 9 Start, 10 L3, 11 R3.
- Discovery: phone broadcasts ASCII `ONYX?` to the subnet broadcast address and
  `255.255.255.255` every second until connected; the laptop replies `ONYX!` + hostname.
  The phone stores the laptop IP in `SharedPreferences`.
- Rumble: laptop → phone `0x02, large, small` (0-255); the phone vibrates with
  `max(large, small)` and stops when both are 0.
- Keepalive: a unicast `ONYX?` every second while connected lets the phone detect a lost
  laptop after 3 s; the laptop resets the virtual pad after 3 s of silence.
