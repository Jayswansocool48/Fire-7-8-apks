#!/usr/bin/env python3
"""A pretend Roku TV for testing Hub Remote and the Hub server: answers the TV control
protocol (port 8060) and logs every key press and launch to roku.log."""
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[1] if len(sys.argv) > 1 else "roku.log"
APPS = [("12", "Netflix", (229, 9, 20)), ("837", "YouTube", (255, 0, 0)), ("2595", "Crunchyroll", (244, 117, 33)),
        ("291097", "Disney+", (17, 60, 207)), ("13", "Prime Video", (0, 168, 225)), ("2285", "Hulu", (28, 231, 131))]
state = {"active": "<app>Roku</app>", "power": "PowerOn"}


def png(rgb, w=290, h=218):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


ICONS = {a[0]: png(a[2]) for a in APPS}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="text/xml"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/query/apps":
            body = "<apps>" + "".join(f'<app id="{i}" type="appl">{n}</app>' for i, n, _ in APPS) + '<app id="dev" type="appl">Hub</app></apps>'
            self.send(200, body.encode())
        elif self.path.startswith("/query/icon/"):
            self.send(200, ICONS.get(self.path.rsplit("/", 1)[1], png((90, 90, 90))), "image/png")
        elif self.path == "/query/active-app":
            self.send(200, f"<active-app>{state['active']}</active-app>".encode())
        elif self.path == "/query/device-info":
            self.send(200, f"<device-info><power-mode>{state['power']}</power-mode></device-info>".encode())
        else:
            self.send(404, b"")

    def do_POST(self):
        with open(LOG, "a") as f:
            f.write("POST " + self.path + "\n")
        if self.path.startswith("/launch/dev"):
            state["active"] = '<app id="dev">Hub</app>'
        elif self.path.startswith("/launch/"):
            app = self.path.split("/")[2].split("?")[0]
            name = next((n for i, n, _ in APPS if i == app), "App")
            state["active"] = f'<app id="{app}">{name}</app>'
        self.send(200, b"")


ThreadingHTTPServer(("0.0.0.0", 8060), H).serve_forever()
