# Onyx Pad – Windows receiver

Turns the phone into a virtual **Xbox 360 controller** over Wi-Fi. No Bluetooth needed.

## 1. One-time setup

1. Install **Python 3** (tick *Add python.exe to PATH*).
2. Get **ViGEmBus** — the driver that provides the virtual controller:
   - Usually `vgamepad` installs everything needed on Windows, but if `receiver.py`
     reports that it cannot create the controller, install ViGEmBus manually from
     <https://github.com/nefarius/ViGEmBus/releases> and reboot.
3. Network:
   - Put the laptop and the phone on the **same Wi-Fi**, or
   - join the **phone's hotspot** from the laptop (works well on the go).
4. Firewall: the first run may pop a Windows Firewall prompt. Allow **Python** for
   **Private networks** so UDP port **7777** is reachable.

## 2. Run

Double-click **`run.bat`** (installs `requirements.txt`, then starts `receiver.py`), or:

```bat
python -m pip install -r requirements.txt
python receiver.py
```

The console prints the laptop's LAN IP and waits for the phone:

```
Onyx Pad receiver ready
  PC        : DESKTOP-1234 (192.168.1.50)
  Listening : UDP 0.0.0.0:7777
```

Open the Onyx Pad app on the phone. The pill should change from
`SEARCHING…` to `CONNECTED · DESKTOP-1234`. Usually nothing else is needed.

If auto-discovery is blocked by the network, tap the pill in the app, type the IP
printed above, and press **Save** (or **Retry**).

## 3. Test

- `Win+R` → `joy.cpl` → **Properties** on "Xbox 360 Controller for Windows".
- Or open <https://gamepad-tester.com> in a browser.

Buttons, D-pad, both sticks and both triggers work, and games can drive phone
rumble (the app vibrates while the game reports rumble).

## Notes

- The app also shows `LOST – RECONNECTING…` if the laptop stops replying for 3 s,
  and reconnects automatically.
- Closing `receiver.py` resets the virtual controller, so no button gets stuck in
  Windows or in a game.
- Only one phone is used at a time; the last one to send packets wins.
