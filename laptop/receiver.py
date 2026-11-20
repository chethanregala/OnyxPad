#!/usr/bin/env python3
"""Onyx Pad - Wi-Fi UDP receiver that emulates an Xbox 360 controller on Windows.

Protocol
--------
phone -> laptop : b"\\x01" + 9 byte pad report (on change and at 20 Hz)
                  b"ONYX?" discovery probe (~1 Hz, also used as a keepalive)
laptop -> phone : b"ONYX!" + hostname   (discovery reply / keepalive)
                  b"\\x02" + large + small   (rumble, each 0-255)

Pad report layout (9 bytes):
    btnLo, btnHi (bits 8-11), hat (0 none, 1 N ... 8 NW),
    lx, ly, rx, ry (signed bytes, y positive = down), L2, R2 (0/255)
"""

import socket
import threading
import time

PORT = 7777
LOST_AFTER = 3.0
RUMBLE_RESEND = 1.5

HOSTNAME = socket.gethostname()
MSG_REPORT = 0x01
MSG_RUMBLE = 0x02
PROBE = b"ONYX?"
REPLY = b"ONYX!"

try:
    import vgamepad as vg
except Exception as exc:  # noqa: BLE001 - show a friendly message for any load failure
    raise SystemExit(
        "Could not import vgamepad (%s).\n"
        "Install the requirements first:  python -m pip install -r requirements.txt\n"
        "If it still fails, install ViGEmBus and reboot." % exc
    )

BUTTON_BITS = [
    (0, vg.XUSB_BUTTON.XUSB_GAMEPAD_A),
    (1, vg.XUSB_BUTTON.XUSB_GAMEPAD_B),
    (2, vg.XUSB_BUTTON.XUSB_GAMEPAD_X),
    (3, vg.XUSB_BUTTON.XUSB_GAMEPAD_Y),
    (4, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER),
    (5, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER),
    (8, vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK),
    (9, vg.XUSB_BUTTON.XUSB_GAMEPAD_START),
    (10, vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB),
    (11, vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB),
]

UP = vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP
DOWN = vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN
LEFT = vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT
RIGHT = vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT

# hat: 0 = centred, 1 = N, then clockwise
DPAD = {
    1: (UP,),
    2: (UP, RIGHT),
    3: (RIGHT,),
    4: (RIGHT, DOWN),
    5: (DOWN,),
    6: (DOWN, LEFT),
    7: (LEFT,),
    8: (LEFT, UP),
}


def clock():
    return time.strftime("%H:%M:%S")


def s8(value):
    """Unsigned byte -> signed byte (-128..127)."""
    return value - 256 if value >= 128 else value


def scale(value):
    """-127..127 -> -32767..32767 for the Xbox stick axes."""
    value = max(-127, min(127, value))
    return int(round(value * 32767.0 / 127.0))


def lan_ip():
    """Best-effort LAN address of this PC (no packet is actually sent)."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


class Receiver:
    def __init__(self):
        self.pad = vg.VX360Gamepad()

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", PORT))
        self.sock.settimeout(0.1)

        self.lock = threading.Lock()
        self.phone = None            # (ip, port) of the last packet
        self.last_packet = 0.0
        self.lost = False
        self.report_addr = None      # sender of the last 0x01 report

        self.pressed = set()
        self.rumble = (0, 0)         # (large, small)
        self.rumble_active = False
        self.rumble_sent = 0.0

        try:
            self.pad.register_notification(callback_function=self._on_rumble)
        except Exception as exc:  # noqa: BLE001
            print("WARNING: vgamepad rumble notifications unavailable (%s)" % exc)
            print("         Phone rumble will not work; everything else is fine.\n")

        self.reset_controller()

    # ---------------------------------------------------------------- rumble
    def _on_rumble(self, client, target, large_motor, small_motor, led_number, user_data):
        large = max(0, min(255, int(large_motor)))
        small = max(0, min(255, int(small_motor)))
        with self.lock:
            self.rumble = (large, small)
            self.send_rumble_locked()

    def send_rumble_locked(self):
        self.rumble_active = max(self.rumble) > 0
        self.rumble_sent = time.monotonic()
        if not self.phone:
            return
        try:
            self.sock.sendto(bytes((MSG_RUMBLE, self.rumble[0], self.rumble[1])), self.phone)
        except OSError as exc:
            print("[%s] rumble send failed: %s" % (clock(), exc))

    # --------------------------------------------------------------- packets
    def mark_link(self, addr, now):
        if self.phone != addr:
            if self.phone is None:
                print("[%s] phone connected from %s:%d" % (clock(), addr[0], addr[1]))
            else:
                print("[%s] phone moved to %s:%d" % (clock(), addr[0], addr[1]))
            self.reset_controller()
        elif self.lost:
            print("[%s] phone reconnected" % clock())
        self.phone = addr
        self.last_packet = now
        self.lost = False

    def handle(self, data, addr):
        if not data:
            return
        now = time.monotonic()

        if data[0] == MSG_REPORT and len(data) >= 10:
            if self.report_addr != addr:
                self.report_addr = addr
                print("[%s] first report from %s:%d" % (clock(), addr[0], addr[1]))
            self.mark_link(addr, now)
            self.apply_report(data[1:10])
            return

        if data[: len(PROBE)] == PROBE:
            print("[%s] ONYX? from %s" % (clock(), addr[0]))
            try:
                self.sock.sendto(REPLY + HOSTNAME.encode("utf-8"), addr)
            except OSError as exc:
                print("[%s] discovery reply failed: %s" % (clock(), exc))
            self.mark_link(addr, now)

    def apply_report(self, report):
        lo, hi, hat = report[0], report[1] & 0x0F, report[2]
        lx, ly, rx, ry = s8(report[3]), s8(report[4]), s8(report[5]), s8(report[6])
        l2, r2 = report[7], report[8]

        mask = lo | (hi << 8)
        wanted = set()
        for bit, button in BUTTON_BITS:
            if mask & (1 << bit):
                wanted.add(button)
        wanted.update(DPAD.get(hat, ()))

        for button in wanted - self.pressed:
            self.pad.press_button(button=button)
        for button in self.pressed - wanted:
            self.pad.release_button(button=button)
        self.pressed = wanted

        # Digital L2/R2 button bits also drive the analog triggers.
        if mask & (1 << 6):
            l2 = 255
        if mask & (1 << 7):
            r2 = 255
        self.pad.left_trigger(value=max(0, min(255, l2)))
        self.pad.right_trigger(value=max(0, min(255, r2)))

        # Phone Y is positive-down, Xbox Y is positive-up, so invert it.
        self.pad.left_joystick(x_value=scale(lx), y_value=-scale(ly))
        self.pad.right_joystick(x_value=scale(rx), y_value=-scale(ry))
        self.pad.update()

    # ---------------------------------------------------------------- housekeeping
    def reset_controller(self):
        try:
            self.pad.reset()
            self.pad.update()
        except Exception as exc:  # noqa: BLE001
            print("[%s] controller reset failed: %s" % (clock(), exc))
        self.pressed = set()

    def on_lost(self):
        print("[%s] no packets for %.0f s - controller reset, waiting for the phone"
              % (clock(), LOST_AFTER))
        self.phone = None
        self.lost = True
        self.report_addr = None
        with self.lock:
            self.rumble = (0, 0)
            self.rumble_active = False
        self.reset_controller()

    def run(self):
        print("Onyx Pad receiver ready")
        print("  PC        : %s (%s)" % (HOSTNAME, lan_ip()))
        print("  Listening : UDP 0.0.0.0:%d" % PORT)
        print("  Same Wi-Fi or phone hotspot required.")
        print("  If Windows Firewall asks, allow Python on private networks (UDP %d)." % PORT)
        print("  Waiting for the phone...\n")

        try:
            while True:
                now = time.monotonic()

                if self.phone is not None and now - self.last_packet > LOST_AFTER:
                    self.on_lost()

                if self.rumble_active and now - self.rumble_sent >= RUMBLE_RESEND:
                    with self.lock:
                        if self.phone:
                            self.send_rumble_locked()

                try:
                    data, addr = self.sock.recvfrom(2048)
                except socket.timeout:
                    continue
                except OSError:
                    break

                try:
                    self.handle(data, addr)
                except Exception as exc:  # noqa: BLE001 - never kill the receiver
                    print("[%s] packet error: %s" % (clock(), exc))
        except KeyboardInterrupt:
            print("\nStopping...")
        finally:
            self.reset_controller()
            try:
                self.sock.close()
            except OSError:
                pass


def main():
    try:
        Receiver().run()
    except Exception as exc:  # noqa: BLE001 - most likely ViGEmBus missing
        print("Could not create the virtual Xbox 360 controller: %s" % exc)
        print("Install ViGEmBus from https://github.com/nefarius/ViGEmBus/releases,")
        print("reboot Windows, then run this again.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
