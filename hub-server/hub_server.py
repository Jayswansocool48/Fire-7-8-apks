#!/usr/bin/env python3
"""
Hub server 3 - the always-on half of the Hub Roku channel and the Hub Remote app.

  * TV web browser: headless Chromium with an ad blocker. Frames stream to the TV only
    when the page changes (Chrome screencast), so it feels much quicker.
  * Hub customization: wallpapers (presets, your photos, Bing photo of the day),
    accent color, which rows/widgets show, clock style.
  * Widget data: weather, calendar agenda, notes, YouTube channels, anime episodes.
  * Media library: play videos, music and photos from your CasaOS folders on the TV.
  * Send to TV: links, photos and videos from your phone/tablet/PC.
  * Sleep timer, auto-open Hub, keep Hub open.
  * Control page at http://<server>:8765 (hub_control.html next to this file).

Runs in the CasaOS app (start.sh) or by hand:  python3 hub_server.py --tv 10.0.0.50
"""
import argparse
import base64
import hashlib
import html
import json
import mimetypes
import os
import queue
import random
import re
import shutil
import socket
import threading
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, quote_plus, unquote, urlencode, urlparse

from playwright.sync_api import Error as PWError
from playwright.sync_api import sync_playwright

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9
    ZoneInfo = None

VERSION = "3.0.0"
W, H = 1280, 720  # the Roku app's canvas, so clicks map 1:1
HERE = os.path.dirname(os.path.abspath(__file__))
LAN = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never proxy TV traffic
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0 Safari/537.36")

VIDEO = {".mp4", ".m4v", ".mkv", ".mov", ".ts", ".m2ts", ".avi", ".wmv", ".webm"}
AUDIO = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma"}
PHOTO = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic"}

DEFAULT_HUB = {
    "wallpaper": {"type": "preset", "value": "aurora"},
    "accent": "#4F7CFF",
    "clock24": False,
    "name": "",
    "units": "F",
    "location": "",
    "rows": [
        {"id": "widgets", "on": True},
        {"id": "youtube", "on": True},
        {"id": "anime", "on": True},
        {"id": "apps", "on": True},
        {"id": "channels", "on": True},
    ],
    "widgets": [
        {"id": "weather", "on": True},
        {"id": "agenda", "on": True},
        {"id": "notes", "on": True},
    ],
    "screensaver": {"minutes": 5, "source": "gallery"},
    "favorites": [],
}
DEFAULTS = {
    "tv_ip": "",
    "port": 8765,
    "home": "https://duckduckgo.com",
    "quality": 62,
    "adblock": True,
    "autolaunch": True,
    "kiosk": False,
    "hub": DEFAULT_HUB,
    "media": [
        {"name": "Media", "path": "/media/Media"},
        {"name": "Gallery", "path": "/media/Gallery"},
        {"name": "Downloads", "path": "/media/Downloads"},
    ],
    "calendar_url": "",
    "events": [],
    "notes": [],
    "youtube": [],
    "anime": [],
    "place": None,
}

# Wallpaper presets, as CSS. The server renders them to images for the TV; the control
# page shows the same CSS as thumbnails, so both always match.
PRESETS = {
    "aurora": "radial-gradient(55% 75% at 12% 8%,#2f5bff 0%,transparent 62%),radial-gradient(50% 70% at 88% 92%,#8a3fd6 0%,transparent 60%),radial-gradient(40% 50% at 55% 78%,#139fb8 0%,transparent 60%),#070a14",
    "sunset": "radial-gradient(70% 90% at 80% 100%,#ff7a3d 0%,transparent 60%),radial-gradient(60% 80% at 20% 90%,#ff2e6e 0%,transparent 62%),radial-gradient(80% 60% at 50% 0%,#3b1366 0%,transparent 70%),#12061f",
    "ocean": "radial-gradient(70% 80% at 0% 100%,#0b8f9c 0%,transparent 60%),radial-gradient(60% 70% at 100% 0%,#1d4ed8 0%,transparent 60%),linear-gradient(160deg,#03111f,#062a3a)",
    "forest": "radial-gradient(60% 80% at 15% 85%,#1f8a4c 0%,transparent 60%),radial-gradient(50% 60% at 85% 15%,#0f5132 0%,transparent 60%),radial-gradient(40% 40% at 60% 60%,#7bb33b33 0%,transparent 70%),#04140c",
    "ember": "radial-gradient(60% 70% at 85% 20%,#ff5a1f 0%,transparent 55%),radial-gradient(50% 70% at 10% 90%,#a1131f 0%,transparent 60%),#140605",
    "glacier": "radial-gradient(60% 80% at 20% 20%,#7dd3fc 0%,transparent 60%),radial-gradient(60% 80% at 80% 90%,#6366f1 0%,transparent 60%),linear-gradient(180deg,#0b1730,#1e2a4a)",
    "candy": "radial-gradient(55% 70% at 15% 15%,#ff5fa2 0%,transparent 60%),radial-gradient(55% 70% at 85% 85%,#5b8cff 0%,transparent 60%),radial-gradient(40% 50% at 70% 25%,#b45cff 0%,transparent 60%),#160b24",
    "midnight": "radial-gradient(80% 60% at 50% 110%,#1e293b 0%,transparent 70%),linear-gradient(180deg,#05060a,#0b0f19)",
}

WMO = {
    0: ("Clear", "sun"), 1: ("Mostly clear", "sun"), 2: ("Partly cloudy", "partly"), 3: ("Cloudy", "cloud"),
    45: ("Fog", "fog"), 48: ("Fog", "fog"), 51: ("Drizzle", "rain"), 53: ("Drizzle", "rain"), 55: ("Drizzle", "rain"),
    56: ("Freezing drizzle", "rain"), 57: ("Freezing drizzle", "rain"), 61: ("Light rain", "rain"), 63: ("Rain", "rain"),
    65: ("Heavy rain", "rain"), 66: ("Freezing rain", "rain"), 67: ("Freezing rain", "rain"), 71: ("Light snow", "snow"),
    73: ("Snow", "snow"), 75: ("Heavy snow", "snow"), 77: ("Snow grains", "snow"), 80: ("Showers", "rain"),
    81: ("Showers", "rain"), 82: ("Heavy showers", "rain"), 85: ("Snow showers", "snow"), 86: ("Snow showers", "snow"),
    95: ("Thunderstorm", "storm"), 96: ("Thunderstorm", "storm"), 99: ("Thunderstorm", "storm"),
}


def log(*args):
    print(time.strftime("%H:%M:%S"), *args, flush=True)


def fetch(url, data=None, headers=None, timeout=20):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def fetch_json(url, payload=None, timeout=20):
    data = json.dumps(payload).encode() if payload is not None else None
    hdr = {"Content-Type": "application/json", "Accept": "application/json"} if payload is not None else {}
    return json.loads(fetch(url, data, hdr, timeout))


def new_id():
    return uuid.uuid4().hex[:10]


def safe_name(name):
    base = os.path.basename(name or "file")
    base = re.sub(r"[^\w.\- ]+", "_", base).strip(" .") or "file"
    return base[:120]


# ---------------------------------------------------------------- config

class Config:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.data = json.loads(json.dumps(DEFAULTS))
        if os.path.exists(path):
            try:
                with open(path) as f:
                    saved = json.load(f)
                hub = {**self.data["hub"], **saved.pop("hub", {})}
                self.data.update(saved)
                self.data["hub"] = hub
            except (OSError, ValueError) as e:
                log("Config unreadable, using defaults:", e)
        self.version = 1

    def __getitem__(self, key):
        return self.data[key]

    def get(self, key, default=None):
        return self.data.get(key, default)

    def update(self, **changes):
        with self.lock:
            self.data.update(changes)
            self.version += 1
            self._save()

    def update_hub(self, changes):
        with self.lock:
            self.data["hub"] = {**self.data["hub"], **changes}
            self.version += 1
            self._save()

    def _save(self):
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f, indent=2)
        os.replace(tmp, self.path)


# ---------------------------------------------------------------- event channel to the TV

class Events:
    """Hub on the TV long-polls this: pointer moves, config changes, toasts..."""

    def __init__(self):
        self.cond = threading.Condition()
        self.items = []  # (seq, event)
        self.seq = 0

    def publish(self, **event):
        with self.cond:
            self.seq += 1
            self.items.append((self.seq, event))
            del self.items[:-300]
            self.cond.notify_all()

    def since(self, after, wait):
        with self.cond:
            if after > self.seq:  # server restarted; resync
                after = 0
            self.cond.wait_for(lambda: self.seq > after, timeout=wait)
            evs = [e for s, e in self.items if s > after]
            # Coalesce pointer moves so a slow TV never falls behind.
            out, dx, dy = [], 0, 0
            for e in evs:
                if e.get("type") == "pointer":
                    dx += e.get("dx", 0)
                    dy += e.get("dy", 0)
                else:
                    if dx or dy:
                        out.append({"type": "pointer", "dx": dx, "dy": dy})
                        dx = dy = 0
                    out.append(e)
            if dx or dy:
                out.append({"type": "pointer", "dx": dx, "dy": dy})
            return self.seq, out


# ---------------------------------------------------------------- Roku control (ECP)

class Roku:
    def __init__(self, cfg):
        self.cfg = cfg
        self.icons = {}

    def _req(self, path, method="GET", timeout=3):
        ip = self.cfg["tv_ip"]
        if not ip:
            raise RuntimeError("TV address not set")
        req = urllib.request.Request(f"http://{ip}:8060{path}", method=method,
                                     data=b"" if method == "POST" else None)
        with LAN.open(req, timeout=timeout) as r:
            return r.read(), r.headers.get("Content-Type", "")

    def apps(self):
        body, _ = self._req("/query/apps")
        root = ET.fromstring(body)
        favs = self.cfg["hub"].get("favorites", [])
        apps = [{"id": a.get("id"), "name": (a.text or "").strip(), "fav": a.get("id") in favs}
                for a in root.findall("app") if a.get("type") == "appl" and a.get("id") != "dev"]
        apps.sort(key=lambda a: (favs.index(a["id"]) if a["fav"] else 999))
        return apps

    def icon(self, app_id):
        hit = self.icons.get(app_id)
        if hit and time.time() - hit[2] < 86400:
            return hit[0], hit[1]
        data, ctype = self._req("/query/icon/" + quote(app_id, safe=""))
        self.icons[app_id] = (data, ctype, time.time())
        return data, ctype

    def active(self):
        body, _ = self._req("/query/active-app")
        app = ET.fromstring(body).find("app")
        if app is None:
            return {"id": None, "name": "Unknown"}
        return {"id": app.get("id"), "name": (app.text or "").strip() or "Home"}

    def power(self):
        body, _ = self._req("/query/device-info", timeout=3)
        node = ET.fromstring(body).find("power-mode")
        return node.text if node is not None else "PowerOn"

    def launch(self, app_id, params=None):
        q = ("?" + urlencode(params)) if params else ""
        self._req(f"/launch/{quote(app_id, safe='')}{q}", "POST")

    def key(self, name):
        self._req("/keypress/" + quote(name, safe=""), "POST")

    def type_text(self, text):
        for ch in text:
            self.key("Lit_" + ch)
            time.sleep(0.03)

    def find_app(self, name, fallback):
        try:
            for a in self.apps():
                if name.lower() in a["name"].lower():
                    return a["id"]
        except Exception:  # noqa: BLE001
            pass
        return fallback

    def open_hub(self, params=None):
        try:
            running = self.active().get("id") == "dev"
        except Exception:  # noqa: BLE001
            running = False
        if running and params:
            self._req("/input?" + urlencode(params), "POST")  # Hub is open: just switch
        elif not running:
            self.launch("dev", params)


def discover_roku(timeout=3.0):
    msg = ("M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\n"
           'Man: "ssdp:discover"\r\nST: roku:ecp\r\nMX: 2\r\n\r\n').encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(msg, ("239.255.255.250", 1900))
        while True:
            data, _ = s.recvfrom(2048)
            for line in data.decode(errors="ignore").splitlines():
                if line.lower().startswith("location:"):
                    return urlparse(line.split(":", 1)[1].strip()).hostname
    except OSError:
        return None
    finally:
        s.close()


# ---------------------------------------------------------------- ad blocker

class AdBlock:
    SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    EXTRA = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "adnxs.com", "taboola.com", "outbrain.com", "criteo.com",
        "amazon-adsystem.com", "scorecardresearch.com", "moatads.com", "pubmatic.com", "rubiconproject.com",
    }

    def __init__(self, cache_path):
        self.cache_path = cache_path
        self.domains = set(self.EXTRA)
        self.blocked = 0
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        while True:
            self._load()
            time.sleep(24 * 3600)

    def _load(self):
        text = None
        fresh = os.path.exists(self.cache_path) and time.time() - os.path.getmtime(self.cache_path) < 3 * 86400
        if not fresh:
            try:
                text = fetch(self.SOURCE, timeout=40).decode("utf-8", "ignore")
                with open(self.cache_path, "w") as f:
                    f.write(text)
            except OSError as e:
                log("Ad list download failed, using cached copy if any:", e)
        if text is None and os.path.exists(self.cache_path):
            with open(self.cache_path) as f:
                text = f.read()
        if not text:
            return
        domains = set(self.EXTRA)
        for line in text.splitlines():
            parts = line.split("#", 1)[0].split()
            if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1"):
                d = parts[1].lower()
                if d not in ("localhost", "0.0.0.0", "local", "broadcasthost"):
                    domains.add(d)
        self.domains = domains
        log(f"Ad blocker loaded {len(domains):,} domains")

    def is_blocked(self, host):
        host = (host or "").lower()
        domains = self.domains
        while host:
            if host in domains:
                return True
            dot = host.find(".")
            if dot < 0:
                return False
            host = host[dot + 1:]
        return False


# ---------------------------------------------------------------- browser + frames

class Frames:
    """Latest browser frame; viewers wait for a newer one instead of polling."""

    def __init__(self):
        self.cond = threading.Condition()
        self.data = None
        self.seq = 0
        self.clients = {}
        self.last_request = 0.0

    def publish(self, data):
        with self.cond:
            self.data = data
            self.seq += 1
            self.cond.notify_all()

    def get(self, client, wait):
        self.last_request = time.time()
        with self.cond:
            last = self.clients.get(client, 0) if client else 0
            if wait and client:
                self.cond.wait_for(lambda: self.seq > last and self.data is not None, timeout=wait)
            if client:
                self.clients[client] = self.seq
                if len(self.clients) > 50:
                    self.clients.pop(next(iter(self.clients)))
            return self.data


def normalize(text):
    t = (text or "").strip()
    if not t:
        return None
    if "://" in t:
        return t
    if " " in t or ("." not in t and not t.startswith("localhost")):
        return "https://duckduckgo.com/?q=" + quote_plus(t)
    return ("http://" if t.startswith("localhost") else "https://") + t


class Browser:
    def __init__(self, cfg, adblock, frames, port):
        self.cfg = cfg
        self.adblock = adblock
        self.frames = frames
        self.port = port
        self.pw = sync_playwright().start()
        self.browser = self.pw.chromium.launch(headless=True, args=["--disable-gpu", "--mute-audio"])
        self.context = self.browser.new_context(
            viewport={"width": W, "height": H}, device_scale_factor=1, locale="en-US",
            color_scheme="dark", user_agent=UA)
        self.context.route("**/*", self._route)
        self.context.on("page", self._adopt)
        self.page = self.context.new_page()
        self.util = None
        self.cast = None
        self.cast_page = None
        self.cast_failed = False
        self.pending = None
        self.last_pub = 0.0
        self.last_shot = 0.0
        self.goto(cfg["home"])

    # -- plumbing
    def _route(self, route):
        if self.cfg["adblock"] and self.adblock.is_blocked(urlparse(route.request.url).hostname):
            self.adblock.blocked += 1
            route.abort("blockedbyclient")
        else:
            route.continue_()

    def _adopt(self, page):
        self.page = page

    def _live(self):
        if self.page.is_closed():
            pages = [p for p in self.context.pages if not p.is_closed()]
            self.page = pages[-1] if pages else self.context.new_page()
        return self.page

    def pump(self, ms=15):
        try:
            self._live().wait_for_timeout(ms)
        except PWError:
            pass

    # -- screencast: Chrome pushes a frame whenever the page repaints
    def tick(self, watching):
        if not watching:
            if self.cast:
                self._stop_cast()
            return
        page = self._live()
        if not self.cast_failed and (self.cast is None or self.cast_page is not page):
            self._start_cast(page)
        now = time.time()
        if self.pending is not None and now - self.last_pub > 0.1:
            self.frames.publish(self.pending)
            self.pending = None
            self.last_pub = now
        if self.cast_failed and now - self.last_shot > 0.5:
            self.snapshot()

    def _start_cast(self, page):
        self._stop_cast()
        try:
            s = self.context.new_cdp_session(page)
            s.on("Page.screencastFrame", lambda p, s=s: self._on_frame(s, p))
            s.send("Page.startScreencast", {"format": "jpeg", "quality": int(self.cfg["quality"]),
                                            "maxWidth": W, "maxHeight": H, "everyNthFrame": 1})
            self.cast, self.cast_page = s, page
            self.snapshot()
        except Exception as e:  # noqa: BLE001
            log("Screencast unavailable, using screenshots:", str(e).splitlines()[0])
            self.cast_failed = True

    def _on_frame(self, session, params):
        try:
            session.send("Page.screencastFrameAck", {"sessionId": params["sessionId"]})
        except Exception:  # noqa: BLE001
            pass
        data = base64.b64decode(params["data"])
        now = time.time()
        if now - self.last_pub >= 0.1:  # at most ~10 frames a second
            self.frames.publish(data)
            self.last_pub = now
            self.pending = None
        else:
            self.pending = data

    def _stop_cast(self):
        if self.cast:
            try:
                self.cast.send("Page.stopScreencast")
                self.cast.detach()
            except Exception:  # noqa: BLE001
                pass
        self.cast = None
        self.cast_page = None

    def snapshot(self):
        self.last_shot = time.time()
        try:
            data = self._live().screenshot(type="jpeg", quality=int(self.cfg["quality"]), timeout=10000)
        except PWError:
            return None
        self.frames.publish(data)
        return data

    # -- actions
    def goto(self, text):
        url = normalize(text)
        if not url:
            return self.info()
        try:
            self._live().goto(url, wait_until="domcontentloaded", timeout=20000)
        except PWError as e:
            log("Could not open", url, "-", str(e).splitlines()[0])
        return self.info()

    def act(self, a):
        p = self._live()
        kind = a.get("type")
        try:
            if kind == "click":
                p.mouse.click(float(a["x"]), float(a["y"]))
            elif kind == "move":
                p.mouse.move(float(a["x"]), float(a["y"]))
            elif kind == "scroll":
                p.mouse.move(float(a.get("x", W / 2)), float(a.get("y", H / 2)))
                p.mouse.wheel(0, float(a.get("dy", 400)))
            elif kind == "goto":
                return self.goto(a.get("url", ""))
            elif kind == "home":
                return self.goto(self.cfg["home"])
            elif kind == "type":
                p.keyboard.type(a.get("text", ""), delay=8)
                if a.get("enter"):
                    p.keyboard.press("Enter")
            elif kind == "key":
                p.keyboard.press(a.get("key", "Enter"))
            elif kind in ("back", "forward", "reload"):
                fn = {"back": p.go_back, "forward": p.go_forward, "reload": p.reload}[kind]
                fn(wait_until="commit", timeout=15000)
        except PWError as e:
            log(kind, "failed:", str(e).splitlines()[0])
        return self.info()

    def info(self):
        p = self._live()
        try:
            title = p.title()
        except PWError:
            title = ""
        return {"ok": True, "url": p.url, "title": title}

    def frame(self):
        return self.snapshot()

    # -- image rendering (resizing photos, drawing preset wallpapers) without extra libraries
    def _util_page(self):
        if self.util is None or self.util.is_closed():
            ctx = self.browser.new_context(device_scale_factor=1)
            self.util = ctx.new_page()
        return self.util

    def render_html(self, body_html, w, h, out, quality=86):
        pg = self._util_page()
        pg.set_viewport_size({"width": w, "height": h})
        pg.set_content(f"<!doctype html><html><body style='margin:0;overflow:hidden;background:#000'>{body_html}</body></html>")
        try:
            pg.wait_for_function("Array.from(document.images).every(i => i.complete)", timeout=20000)
        except PWError:
            pass
        data = pg.screenshot(type="jpeg", quality=quality)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out + ".tmp", "wb") as f:
            f.write(data)
        os.replace(out + ".tmp", out)
        return out

    def render_image(self, src_url, w, h, out, fit="cover"):
        img = (f"<img src='{html.escape(src_url, quote=True)}' "
               f"style='width:{w}px;height:{h}px;object-fit:{fit};display:block'>")
        return self.render_html(img, w, h, out)

    def render_preset(self, name, out):
        css = PRESETS.get(name, PRESETS["aurora"])
        return self.render_html(f"<div style='width:{W}px;height:{H}px;background:{css}'></div>", W, H, out, 90)

    def close(self):
        try:
            self.browser.close()
        finally:
            self.pw.stop()


class BrowserProxy:
    """Web requests run on their own threads, but Playwright must stay on the main
    thread. Browser calls are queued here and run by the main loop."""

    def __init__(self):
        self.jobs = queue.Queue()

    def call(self, fn, *args, timeout=60):
        box = {"done": threading.Event()}
        self.jobs.put((fn, args, box))
        if not box["done"].wait(timeout):
            raise RuntimeError("browser is busy")
        if "error" in box:
            raise box["error"]
        return box["result"]

    def run_pending(self, browser, wait):
        try:
            fn, args, box = self.jobs.get(timeout=wait)
        except queue.Empty:
            return False
        try:
            box["result"] = getattr(browser, fn)(*args)
        except Exception as e:  # noqa: BLE001
            box["error"] = e
        box["done"].set()
        return True


# ---------------------------------------------------------------- media library

class Library:
    def __init__(self, cfg, data_dir):
        self.cfg = cfg
        self.inbox = os.path.join(data_dir, "inbox")
        os.makedirs(self.inbox, exist_ok=True)

    def roots(self):
        out = {"Sent to TV": self.inbox}
        for r in self.cfg["media"]:
            if r.get("path") and os.path.isdir(r["path"]):
                out[r.get("name") or os.path.basename(r["path"])] = r["path"]
        return out

    @staticmethod
    def kind(name):
        ext = os.path.splitext(name)[1].lower()
        if ext in VIDEO:
            return "video"
        if ext in AUDIO:
            return "audio"
        if ext in PHOTO:
            return "photo"
        return None

    def resolve(self, vpath):
        """'Gallery/2024/a.jpg' -> absolute path, refusing anything outside the roots."""
        vpath = (vpath or "").strip("/")
        if not vpath:
            return None, None
        root_name, _, rest = vpath.partition("/")
        roots = self.roots()
        if root_name not in roots:
            raise FileNotFoundError(vpath)
        base = os.path.realpath(roots[root_name])
        full = os.path.realpath(os.path.join(base, rest))
        if full != base and not full.startswith(base + os.sep):
            raise PermissionError(vpath)
        return full, root_name

    def list(self, vpath):
        vpath = (vpath or "").strip("/")
        if not vpath:
            return {"path": "", "dirs": [{"name": n, "path": n} for n in self.roots()], "files": []}
        full, _ = self.resolve(vpath)
        dirs, files = [], []
        for e in sorted(os.scandir(full), key=lambda e: e.name.lower()):
            if e.name.startswith("."):
                continue
            p = vpath + "/" + e.name
            if e.is_dir():
                dirs.append({"name": e.name, "path": p})
            else:
                k = self.kind(e.name)
                if k:
                    files.append({"name": e.name, "path": p, "kind": k, "size": e.stat().st_size})
        return {"path": vpath, "dirs": dirs, "files": files[:2000]}

    def photos(self, root_name, limit=60):
        roots = self.roots()
        base = roots.get(root_name)
        found = []
        if base:
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                for f in filenames:
                    if os.path.splitext(f)[1].lower() in (".jpg", ".jpeg", ".png", ".webp"):
                        rel = os.path.relpath(os.path.join(dirpath, f), base)
                        found.append(root_name + "/" + rel.replace(os.sep, "/"))
                if len(found) > 3000:
                    break
        random.shuffle(found)
        return found[:limit]


# ---------------------------------------------------------------- widgets

def parse_ics_time(value, params):
    value = value.strip()
    if "VALUE=DATE" in params or re.fullmatch(r"\d{8}", value):
        d = datetime.strptime(value[:8], "%Y%m%d")
        return d.replace(tzinfo=None), True
    if value.endswith("Z"):
        return datetime.strptime(value, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc), False
    dt = datetime.strptime(value[:15], "%Y%m%dT%H%M%S")
    m = re.search(r"TZID=([^;:]+)", params)
    if m and ZoneInfo:
        try:
            return dt.replace(tzinfo=ZoneInfo(m.group(1))), False
        except Exception:  # noqa: BLE001
            pass
    return dt.astimezone(), False


def ics_events(text, days=14):
    text = re.sub(r"\r?\n[ \t]", "", text)
    now = datetime.now().astimezone()
    end = now + timedelta(days=days)
    out = []
    for block in re.findall(r"BEGIN:VEVENT(.*?)END:VEVENT", text, re.S):
        props = {}
        for line in block.splitlines():
            if ":" not in line:
                continue
            key, val = line.split(":", 1)
            name, _, params = key.partition(";")
            props.setdefault(name.upper(), (val, params))
        if "DTSTART" not in props:
            continue
        try:
            start, allday = parse_ics_time(*props["DTSTART"])
        except ValueError:
            continue
        title = props.get("SUMMARY", ("(no title)", ""))[0].replace("\\,", ",").replace("\\n", " ")
        starts = [start]
        rule = props.get("RRULE", ("", ""))[0]
        if rule:
            r = dict(p.split("=", 1) for p in rule.split(";") if "=" in p)
            step = {"DAILY": timedelta(days=1), "WEEKLY": timedelta(weeks=1)}.get(r.get("FREQ"))
            interval = int(r.get("INTERVAL", 1))
            until = None
            if "UNTIL" in r:
                try:
                    until, _ = parse_ics_time(r["UNTIL"], "")
                except ValueError:
                    until = None
            count = int(r.get("COUNT", 100000))
            cur = start
            n = 1
            if step:
                byday = r.get("BYDAY")
                days_map = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]
                while n < count:
                    if byday and r.get("FREQ") == "WEEKLY":
                        cur = cur + timedelta(days=1)
                        if _cmp(cur) > _cmp(end):
                            break
                        if days_map[cur.weekday()] not in byday:
                            continue
                    else:
                        cur = cur + step * interval
                    n += 1
                    if until and _cmp(cur) > _cmp(until):
                        break
                    if _cmp(cur) > _cmp(end):
                        break
                    starts.append(cur)
            elif r.get("FREQ") in ("MONTHLY", "YEARLY"):
                for k in range(1, 40):
                    try:
                        if r["FREQ"] == "MONTHLY":
                            y, mo = divmod(start.month - 1 + k * interval, 12)
                            cur = start.replace(year=start.year + y, month=mo + 1)
                        else:
                            cur = start.replace(year=start.year + k * interval)
                    except ValueError:
                        continue
                    if _cmp(cur) > _cmp(end) or k >= count:
                        break
                    starts.append(cur)
        for s in starts:
            if _cmp(now) - 3600 * (24 if allday else 1) <= _cmp(s) <= _cmp(end):
                out.append({"title": title, "start": _cmp(s), "allDay": allday})
    return out


def _cmp(dt):
    """Epoch seconds for aware or naive (local) datetimes."""
    if dt.tzinfo is None:
        return dt.timestamp()
    return dt.timestamp()


class Widgets:
    def __init__(self, cfg, events, roku):
        self.cfg = cfg
        self.events = events
        self.roku = roku
        self.data = {"weather": None, "agenda": [], "youtube": [], "anime": []}
        self.stamp = {}
        self.dirty = set()
        self.wake = threading.Event()
        threading.Thread(target=self._loop, daemon=True).start()

    def refresh(self, *names):
        self.dirty.update(names or ("weather", "agenda", "youtube", "anime"))
        self.wake.set()

    def _due(self, name, every):
        return name in self.dirty or time.time() - self.stamp.get(name, 0) > every

    def _loop(self):
        while True:
            changed = False
            for name, every, fn in (("weather", 900, self._weather), ("agenda", 900, self._agenda),
                                    ("youtube", 1800, self._youtube), ("anime", 3600, self._anime)):
                if self._due(name, every):
                    self.dirty.discard(name)
                    try:
                        value = fn()
                        if value != self.data.get(name):
                            self.data[name] = value
                            changed = True
                    except Exception as e:  # noqa: BLE001
                        log(f"{name} widget:", e)
                    self.stamp[name] = time.time()
            if changed:
                self.events.publish(type="widgets")
            self.wake.wait(60)
            self.wake.clear()

    def snapshot(self):
        d = dict(self.data)
        d["notes"] = self.cfg["notes"][:20]
        d["agenda"] = self._merge_local(d.get("agenda") or [])
        return d

    # weather (Open-Meteo, no key needed)
    def set_location(self, text):
        text = (text or "").strip()
        if not text:
            self.cfg.update(place=None)
            self.cfg.update_hub({"location": ""})
            self.refresh("weather")
            return None
        q = text.split(",")[0].strip()
        res = fetch_json("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name=" + quote(q))
        hits = res.get("results") or []
        if not hits:
            raise ValueError(f"Couldn't find '{text}'")
        rest = text.lower()
        best = next((h for h in hits if (h.get("admin1") or "").lower() in rest and h.get("admin1")), hits[0])
        place = {"name": best["name"], "region": best.get("admin1", ""), "lat": best["latitude"], "lon": best["longitude"]}
        self.cfg.update(place=place)
        self.cfg.update_hub({"location": text})
        self.refresh("weather")
        return place

    def _weather(self):
        place = self.cfg.get("place")
        if not place:
            return None
        f = self.cfg["hub"].get("units", "F") == "F"
        url = ("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
               "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
               "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
               "&timezone=auto&forecast_days=5"
               + ("&temperature_unit=fahrenheit&wind_speed_unit=mph" if f else "")).format(**place)
        d = fetch_json(url)
        cur = d["current"]
        text, icon = WMO.get(cur["weather_code"], ("", "cloud"))
        if icon in ("sun", "partly") and not cur.get("is_day", 1):
            icon = "moon" if icon == "sun" else "moonpartly"
        days = []
        for i, day in enumerate(d["daily"]["time"]):
            t, ic = WMO.get(d["daily"]["weather_code"][i], ("", "cloud"))
            days.append({"day": "Today" if i == 0 else datetime.strptime(day, "%Y-%m-%d").strftime("%a"),
                         "hi": round(d["daily"]["temperature_2m_max"][i]), "lo": round(d["daily"]["temperature_2m_min"][i]),
                         "icon": ic, "rain": d["daily"]["precipitation_probability_max"][i] or 0})
        return {"place": place["name"], "temp": round(cur["temperature_2m"]), "feels": round(cur["apparent_temperature"]),
                "text": text, "icon": icon, "humidity": cur.get("relative_humidity_2m"),
                "wind": round(cur.get("wind_speed_10m") or 0), "unit": "F" if f else "C", "days": days}

    # calendar
    def _agenda(self):
        url = (self.cfg.get("calendar_url") or "").strip()
        if not url:
            return []
        if url.startswith("webcal://"):
            url = "https://" + url[len("webcal://"):]
        evs = ics_events(fetch(url, timeout=30).decode("utf-8", "ignore"))
        return sorted(evs, key=lambda e: e["start"])[:30]

    def _merge_local(self, agenda):
        local = []
        now = time.time()
        for e in self.cfg["events"]:
            if e.get("start", 0) >= now - 3600:
                local.append({"title": e["title"], "start": e["start"], "allDay": e.get("allDay", False), "id": e["id"]})
        return sorted(agenda + local, key=lambda e: e["start"])[:12]

    # YouTube: latest uploads from channels you follow (public RSS, no key)
    @staticmethod
    def resolve_youtube(text):
        t = text.strip()
        m = re.search(r"(UC[0-9A-Za-z_-]{22})", t)
        if m and "/@" not in t:
            cid = m.group(1)
            name = None
        else:
            if t.startswith("@"):
                t = "https://www.youtube.com/" + t
            elif "youtube.com" not in t and "youtu.be" not in t:
                t = "https://www.youtube.com/@" + t.lstrip("@").replace(" ", "")
            page = fetch(t, headers={"Cookie": "CONSENT=YES+1; SOCS=CAI", "Accept-Language": "en-US"}).decode("utf-8", "ignore")
            m = (re.search(r'"externalId":"(UC[0-9A-Za-z_-]{22})"', page)
                 or re.search(r'<meta itemprop="identifier" content="(UC[0-9A-Za-z_-]{22})"', page)
                 or re.search(r'channel_id=(UC[0-9A-Za-z_-]{22})', page)
                 or re.search(r'"channelId":"(UC[0-9A-Za-z_-]{22})"', page))
            if not m:
                raise ValueError("Couldn't find that YouTube channel")
            cid = m.group(1)
            n = re.search(r'<meta property="og:title" content="([^"]+)"', page)
            name = html.unescape(n.group(1)) if n else None
        feed = fetch(f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}")
        root = ET.fromstring(feed)
        title = root.findtext("{http://www.w3.org/2005/Atom}title")
        return {"id": cid, "name": name or title or cid}

    def _youtube(self):
        ns = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015",
              "m": "http://search.yahoo.com/mrss/"}
        vids = []
        for ch in self.cfg["youtube"]:
            try:
                root = ET.fromstring(fetch(f"https://www.youtube.com/feeds/videos.xml?channel_id={ch['id']}"))
            except Exception as e:  # noqa: BLE001
                log("YouTube feed", ch.get("name"), e)
                continue
            for e in root.findall("a:entry", ns)[:8]:
                vid = e.findtext("yt:videoId", namespaces=ns)
                link = e.find("a:link", ns)
                href = link.get("href") if link is not None else ""
                if "/shorts/" in href:
                    continue
                pub = e.findtext("a:published", namespaces=ns) or ""
                try:
                    ts = datetime.fromisoformat(pub.replace("Z", "+00:00")).timestamp()
                except ValueError:
                    ts = 0
                vids.append({"id": vid, "title": e.findtext("a:title", namespaces=ns), "channel": ch["name"],
                             "published": ts, "thumb": f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg"})
        vids.sort(key=lambda v: -v["published"])
        return vids[:20]

    # anime (AniList, no key): next episodes of shows you follow, or what airs today
    Q_FIELDS = "id title{romaji english} coverImage{large} status episodes nextAiringEpisode{episode airingAt} siteUrl"

    @classmethod
    def anime_search(cls, q):
        query = "query($q:String){Page(perPage:10){media(search:$q,type:ANIME,isAdult:false){%s}}}" % cls.Q_FIELDS
        res = fetch_json("https://graphql.anilist.co", {"query": query, "variables": {"q": q}})
        return [cls._anime_item(m) for m in res["data"]["Page"]["media"]]

    @staticmethod
    def _anime_item(m):
        nxt = m.get("nextAiringEpisode") or {}
        return {"id": m["id"], "title": m["title"].get("english") or m["title"]["romaji"],
                "cover": m["coverImage"]["large"], "status": m.get("status"),
                "episode": nxt.get("episode"), "airingAt": nxt.get("airingAt"), "episodes": m.get("episodes")}

    def _anime(self):
        ids = [a["id"] for a in self.cfg["anime"]]
        if ids:
            query = "query($ids:[Int]){Page(perPage:50){media(id_in:$ids,type:ANIME){%s}}}" % self.Q_FIELDS
            res = fetch_json("https://graphql.anilist.co", {"query": query, "variables": {"ids": ids}})
            items = [self._anime_item(m) for m in res["data"]["Page"]["media"]]
            items.sort(key=lambda a: a["airingAt"] or 9e12)
            return items
        now = int(time.time())
        query = ("query($a:Int,$b:Int){Page(perPage:30){airingSchedules(airingAt_greater:$a,airingAt_lesser:$b,sort:TIME)"
                 "{episode airingAt media{id title{romaji english} coverImage{large} isAdult popularity}}}}")
        res = fetch_json("https://graphql.anilist.co", {"query": query, "variables": {"a": now - 6 * 3600, "b": now + 24 * 3600}})
        items = []
        for s in res["data"]["Page"]["airingSchedules"]:
            m = s["media"]
            if m.get("isAdult"):
                continue
            items.append({"id": m["id"], "title": m["title"].get("english") or m["title"]["romaji"],
                          "cover": m["coverImage"]["large"], "episode": s["episode"], "airingAt": s["airingAt"],
                          "pop": m.get("popularity") or 0})
        items.sort(key=lambda a: -a["pop"])
        return sorted(items[:15], key=lambda a: a["airingAt"])


# ---------------------------------------------------------------- the TV watcher (auto-open, kiosk, sleep timer)

class Sleep:
    def __init__(self):
        self.at = None
        self.warned = False

    def set(self, minutes):
        self.at = time.time() + minutes * 60 if minutes else None
        self.warned = False

    def remaining(self):
        return max(0, int(self.at - time.time())) if self.at else None


def watch_tv(roku, cfg, events, sleep, stop):
    last_power = None
    first = True
    home_since = None
    next_poll = 0
    while not stop.wait(2):
        if sleep.at:
            left = sleep.at - time.time()
            if left <= 60 and not sleep.warned:
                sleep.warned = True
                events.publish(type="toast", text="Sleep timer: turning the TV off in 1 minute")
            if left <= 0:
                sleep.at = None
                log("Sleep timer - turning the TV off")
                try:
                    roku.key("PowerOff")
                except Exception as e:  # noqa: BLE001
                    log("Sleep timer couldn't reach the TV:", e)
        if time.time() < next_poll or not cfg["tv_ip"]:
            continue
        next_poll = time.time() + 5
        try:
            power = roku.power()
        except Exception:  # noqa: BLE001
            last_power, home_since = None, None
            continue
        try:
            on = power == "PowerOn"
            if on and last_power != "PowerOn" and not first and cfg["autolaunch"]:
                time.sleep(4)
                log("TV turned on - opening Hub")
                roku.launch("dev")
            if on and cfg["kiosk"]:
                active = roku.active()
                if active["id"] is None:
                    home_since = home_since or time.time()
                    if time.time() - home_since >= 45:
                        log("Idle on the home screen - returning to Hub")
                        roku.launch("dev")
                        home_since = None
                else:
                    home_since = None
        except Exception as e:  # noqa: BLE001
            log("TV watcher:", e)
        last_power, first = power, False


# ---------------------------------------------------------------- web server

class Handler(BaseHTTPRequestHandler):
    S = None  # the App, set in main()
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        pass

    # -- helpers
    def _send(self, code, body, ctype, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code, obj):
        self._send(code, json.dumps(obj).encode(), "application/json")

    def _ok(self, **extra):
        self._json(200, {"ok": True, **extra})

    def _err(self, code, msg):
        self._json(code, {"ok": False, "error": msg})

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return {}

    def _upload(self, folder, max_mb=4000):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > max_mb * 1024 * 1024:
            raise ValueError("Empty or too large")
        name = safe_name(unquote(self.headers.get("X-Filename") or "upload"))
        os.makedirs(folder, exist_ok=True)
        dest = os.path.join(folder, time.strftime("%Y%m%d-%H%M%S-") + name)
        left = length
        with open(dest + ".part", "wb") as f:
            while left > 0:
                chunk = self.rfile.read(min(1 << 20, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        if left:
            os.remove(dest + ".part")
            raise ValueError("Upload interrupted")
        os.replace(dest + ".part", dest)
        return dest

    def _tv(self, fn):
        try:
            result = fn()
            self._json(200, result if isinstance(result, dict) else {"ok": True})
        except Exception as e:  # noqa: BLE001
            self._err(502, f"Couldn't reach the TV: {e}")

    def _file(self, path, ctype=None):
        if not path or not os.path.isfile(path):
            self._err(404, "not found")
            return
        ctype = ctype or mimetypes.guess_type(path)[0] or "application/octet-stream"
        if path.lower().endswith(".mkv"):
            ctype = "video/x-matroska"
        size = os.path.getsize(path)
        start, end, code = 0, size - 1, 200
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].split(",")[0].partition("-")
            try:
                if a:
                    start = int(a)
                    end = int(b) if b else size - 1
                else:
                    start = max(0, size - int(b))
                end = min(end, size - 1)
                code = 206
            except ValueError:
                pass
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Access-Control-Allow-Origin", "*")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with open(path, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # -- routes
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Filename")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        S = self.S
        u = urlparse(self.path)
        path, q = u.path, {k: v[-1] for k, v in parse_qs(u.query).items()}
        try:
            if path in ("/", "/index.html"):
                page = os.path.join(HERE, "hub_control.html")
                if os.path.exists(page):
                    self._file(page, "text/html; charset=utf-8")
                else:
                    self._send(200, b"<h1>Hub server is running</h1><p>Put hub_control.html next to hub_server.py for the control page.</p>", "text/html")
            elif path == "/frame.jpg":
                data = S.frames.get(q.get("c"), 8.0 if q.get("wait") else 0)
                if data is None:
                    data = S.proxy.call("frame")
                if data is None:
                    self._err(503, "no frame yet")
                else:
                    self._send(200, data, "image/jpeg")
            elif path in ("/status", "/api/status"):
                self._json(200, S.status())
            elif path == "/api/hub/state":
                self._json(200, S.hub_state())
            elif path == "/api/hub/events":
                seq, evs = S.events.since(int(q.get("since", 0)), min(25.0, float(q.get("wait", 20))))
                self._json(200, {"ok": True, "seq": seq, "events": evs})
            elif path == "/api/presets":
                self._json(200, {"ok": True, "presets": [{"name": k, "css": v} for k, v in PRESETS.items()]})
            elif path.startswith("/api/presets/") and path.endswith(".jpg"):
                name = path[len("/api/presets/"):-4]
                if name not in PRESETS:
                    raise FileNotFoundError(name)
                out = os.path.join(S.cache, "presets", name + ".jpg")
                if not os.path.exists(out):
                    S.proxy.call("render_preset", name, out)
                self._file(out, "image/jpeg")
            elif path == "/api/wallpaper.jpg":
                self._file(S.wallpaper_file(), "image/jpeg")
            elif path == "/api/wallpapers":
                self._json(200, {"ok": True, "wallpapers": S.list_wallpapers()})
            elif path.startswith("/api/wallpapers/"):
                name = safe_name(unquote(path.rsplit("/", 1)[1]))
                self._file(os.path.join(S.wall_dir, name), "image/jpeg")
            elif path == "/api/img":
                self._file(S.image(q.get("src", ""), int(q.get("w", 1280)), int(q.get("h", 720)), q.get("fit", "contain")), "image/jpeg")
            elif path == "/api/screensaver":
                self._json(200, {"ok": True, "photos": S.screensaver_photos()})
            elif path == "/api/library":
                self._json(200, {"ok": True, **S.library.list(q.get("path", ""))})
            elif path.startswith("/media/"):
                full, _ = S.library.resolve(unquote(path[len("/media/"):]))
                self._file(full)
            elif path == "/api/tv/apps":
                self._tv(lambda: {"ok": True, "apps": S.roku.apps()})
            elif path == "/api/tv/active":
                self._tv(lambda: {"ok": True, "app": S.roku.active()})
            elif path.startswith("/api/tv/icon/"):
                try:
                    data, ctype = S.roku.icon(unquote(path.rsplit("/", 1)[1]))
                    self._send(200, data, ctype or "image/png", {"Cache-Control": "max-age=86400"})
                except Exception:  # noqa: BLE001
                    self._send(404, b"", "text/plain")
            elif path == "/api/anime/search":
                self._json(200, {"ok": True, "results": Widgets.anime_search(q.get("q", ""))})
            elif path == "/api/widgets":
                self._json(200, {"ok": True, **S.widgets.snapshot()})
            else:
                self._err(404, "not found")
        except (FileNotFoundError, PermissionError):
            self._err(404, "not found")
        except Exception as e:  # noqa: BLE001
            log("GET", path, "failed:", e)
            self._err(500, str(e))

    def do_POST(self):
        S = self.S
        path = urlparse(self.path).path
        try:
            # uploads first (raw body, not JSON)
            if path == "/api/wallpapers/upload":
                src = self._upload(os.path.join(S.data_dir, "uploads"), 60)
                self._ok(**S.add_wallpaper(src))
                return
            if path == "/api/send/file":
                dest = self._upload(S.library.inbox)
                self._ok(**S.show_on_tv("Sent to TV/" + os.path.basename(dest)))
                return

            b = self._body()
            if path == "/action":
                self._json(200, S.proxy.call("act", b))
            elif path == "/api/tv/launch":
                self._tv(lambda: S.roku.launch(str(b.get("id", "")), b.get("params")))
            elif path == "/api/tv/key":
                self._tv(lambda: S.roku.key(str(b.get("key", ""))))
            elif path == "/api/tv/text":
                self._tv(lambda: S.roku.type_text(str(b.get("text", ""))))
            elif path == "/api/tv/hub":
                if b.get("url"):
                    S.proxy.call("goto", b["url"])
                params = {"app": b["app"]} if b.get("app") else None
                self._tv(lambda: S.roku.open_hub(params))
            elif path == "/api/tv/favorite":
                favs = [f for f in S.cfg["hub"]["favorites"] if f != b.get("id")]
                if b.get("fav"):
                    favs.append(b.get("id"))
                S.cfg.update_hub({"favorites": favs})
                S.events.publish(type="config")
                self._ok(favorites=favs)
            elif path == "/api/tv/play":
                self._tv(lambda: S.show_on_tv(str(b.get("path", ""))))
            elif path == "/api/remote":  # trackpad: pointer moves, clicks, scrolls for Hub's browser
                S.events.publish(**{k: b[k] for k in ("type", "dx", "dy") if k in b})
                self._ok()
            elif path == "/api/hub/config":
                allowed = {k: v for k, v in b.items() if k in DEFAULT_HUB}
                S.cfg.update_hub(allowed)
                if "units" in allowed:
                    S.widgets.refresh("weather")
                S.events.publish(type="config")
                self._ok(hub=S.cfg["hub"])
            elif path == "/api/wallpapers/delete":
                name = safe_name(b.get("name", ""))
                p = os.path.join(S.wall_dir, name)
                if os.path.isfile(p):
                    os.remove(p)
                self._ok(wallpapers=S.list_wallpapers())
            elif path == "/api/weather/location":
                place = S.widgets.set_location(b.get("location", ""))
                self._ok(place=place)
            elif path == "/api/calendar":
                S.cfg.update(calendar_url=(b.get("url") or "").strip())
                S.widgets.refresh("agenda")
                self._ok()
            elif path == "/api/events/add":
                ev = {"id": new_id(), "title": str(b.get("title", ""))[:120], "start": float(b["start"]),
                      "allDay": bool(b.get("allDay"))}
                S.cfg.update(events=(S.cfg["events"] + [ev])[-100:])
                S.events.publish(type="widgets")
                self._ok(event=ev)
            elif path == "/api/events/delete":
                S.cfg.update(events=[e for e in S.cfg["events"] if e["id"] != b.get("id")])
                S.events.publish(type="widgets")
                self._ok()
            elif path == "/api/notes/add":
                note = {"id": new_id(), "text": str(b.get("text", ""))[:500], "color": b.get("color") or "#FFD166",
                        "created": time.time()}
                S.cfg.update(notes=([note] + S.cfg["notes"])[:30])
                S.events.publish(type="widgets")
                S.events.publish(type="toast", text="New note: " + note["text"][:60])
                self._ok(note=note)
            elif path == "/api/notes/delete":
                S.cfg.update(notes=[n for n in S.cfg["notes"] if n["id"] != b.get("id")])
                S.events.publish(type="widgets")
                self._ok()
            elif path == "/api/youtube/add":
                ch = Widgets.resolve_youtube(str(b.get("channel", "")))
                if not any(c["id"] == ch["id"] for c in S.cfg["youtube"]):
                    S.cfg.update(youtube=S.cfg["youtube"] + [ch])
                S.widgets.refresh("youtube")
                self._ok(channel=ch, channels=S.cfg["youtube"])
            elif path == "/api/youtube/delete":
                S.cfg.update(youtube=[c for c in S.cfg["youtube"] if c["id"] != b.get("id")])
                S.widgets.refresh("youtube")
                self._ok(channels=S.cfg["youtube"])
            elif path == "/api/anime/add":
                item = {"id": int(b["id"]), "title": b.get("title", "")}
                if not any(a["id"] == item["id"] for a in S.cfg["anime"]):
                    S.cfg.update(anime=S.cfg["anime"] + [item])
                S.widgets.refresh("anime")
                self._ok(anime=S.cfg["anime"])
            elif path == "/api/anime/delete":
                S.cfg.update(anime=[a for a in S.cfg["anime"] if a["id"] != int(b.get("id", 0))])
                S.widgets.refresh("anime")
                self._ok(anime=S.cfg["anime"])
            elif path == "/api/send/link":
                self._tv(lambda: S.send_link(str(b.get("url", ""))))
            elif path == "/api/sleep":
                S.sleep.set(float(b.get("minutes") or 0))
                S.events.publish(type="widgets")
                self._ok(remaining=S.sleep.remaining())
            elif path == "/api/settings":
                allowed = {k: b[k] for k in ("tv_ip", "home", "adblock", "autolaunch", "kiosk", "quality", "media") if k in b}
                S.cfg.update(**allowed)
                self._json(200, S.status())
            else:
                self._err(404, "not found")
        except Exception as e:  # noqa: BLE001
            log("POST", path, "failed:", e)
            self._err(400 if isinstance(e, (ValueError, KeyError)) else 500, str(e))


# ---------------------------------------------------------------- the app

class App:
    def __init__(self, cfg, data_dir):
        self.cfg = cfg
        self.data_dir = data_dir
        self.cache = os.path.join(data_dir, "cache")
        self.wall_dir = os.path.join(data_dir, "wallpapers")
        os.makedirs(self.cache, exist_ok=True)
        os.makedirs(self.wall_dir, exist_ok=True)
        self.events = Events()
        self.frames = Frames()
        self.proxy = BrowserProxy()
        self.roku = Roku(cfg)
        self.sleep = Sleep()
        self.adblock = AdBlock(os.path.join(data_dir, "adblock-hosts.txt"))
        self.library = Library(cfg, data_dir)
        self.widgets = Widgets(cfg, self.events, self.roku)
        self.browser = None

    def status(self):
        c = self.cfg
        try:
            info = self.proxy.call("info", timeout=5)
        except Exception:  # noqa: BLE001
            info = {"url": "", "title": ""}
        return {
            "ok": True, "version": VERSION, "browser": info,
            "adblock": {"enabled": c["adblock"], "domains": len(self.adblock.domains), "blocked": self.adblock.blocked},
            "sleep": self.sleep.remaining(),
            "settings": {k: c[k] for k in ("tv_ip", "home", "adblock", "autolaunch", "kiosk", "quality", "media", "calendar_url")},
            "hub": c["hub"], "place": c.get("place"), "youtube": c["youtube"], "anime": c["anime"],
            "events": c["events"], "notes": c["notes"],
        }

    def hub_state(self):
        return {"ok": True, "hub": self.cfg["hub"], "wallpaper": "/api/wallpaper.jpg?v=" + self.wallpaper_key(),
                "widgets": self.widgets.snapshot(), "sleep": self.sleep.remaining(),
                "adblock": {"enabled": self.cfg["adblock"], "blocked": self.adblock.blocked},
                "server_time": int(time.time())}

    # -- wallpapers
    def wallpaper_key(self):
        w = self.cfg["hub"]["wallpaper"]
        extra = date.today().isoformat() if w.get("type") == "bing" else ""
        return hashlib.sha1(json.dumps(w, sort_keys=True).encode() + extra.encode()).hexdigest()[:10]

    def wallpaper_file(self):
        w = self.cfg["hub"]["wallpaper"]
        kind, value = w.get("type"), w.get("value")
        try:
            if kind == "custom":
                p = os.path.join(self.wall_dir, safe_name(value or ""))
                if os.path.isfile(p):
                    return p
            elif kind == "bing":
                return self.bing_wallpaper()
            name = value if kind == "preset" and value in PRESETS else "aurora"
            out = os.path.join(self.cache, "presets", name + ".jpg")
            if not os.path.exists(out):
                self.proxy.call("render_preset", name, out)
            return out
        except Exception as e:  # noqa: BLE001
            log("Wallpaper:", e)
            out = os.path.join(self.cache, "presets", "aurora.jpg")
            if not os.path.exists(out):
                self.proxy.call("render_preset", "aurora", out)
            return out

    def bing_wallpaper(self):
        out = os.path.join(self.cache, f"bing-{date.today().isoformat()}.jpg")
        if not os.path.exists(out):
            d = fetch_json("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US")
            img = "https://www.bing.com" + d["images"][0]["url"]
            self.proxy.call("render_image", img, W, H, out, "cover")
            for f in os.listdir(self.cache):
                if f.startswith("bing-") and f != os.path.basename(out):
                    os.remove(os.path.join(self.cache, f))
        return out

    def list_wallpapers(self):
        return sorted((f for f in os.listdir(self.wall_dir) if f.endswith(".jpg")), reverse=True)

    def add_wallpaper(self, src):
        name = os.path.splitext(os.path.basename(src))[0] + ".jpg"
        out = os.path.join(self.wall_dir, safe_name(name))
        try:
            self.proxy.call("render_image", self.local_url(src), W, H, out, "cover")
        finally:
            os.remove(src)
        self.cfg.update_hub({"wallpaper": {"type": "custom", "value": os.path.basename(out)}})
        self.events.publish(type="config")
        return {"name": os.path.basename(out), "wallpapers": self.list_wallpapers()}

    def local_url(self, abs_path):
        """A URL Chromium (inside this server) can load a local file from."""
        token = hashlib.sha1(abs_path.encode()).hexdigest()[:16]
        Internal.files[token] = abs_path
        return f"http://127.0.0.1:{self.cfg['port']}/internal/{token}"

    # -- photos, resized for the TV and cached
    def image(self, vpath, w, h, fit):
        full, _ = self.library.resolve(vpath)
        if not full or not os.path.isfile(full):
            raise FileNotFoundError(vpath)
        w, h = max(64, min(w, 1920)), max(64, min(h, 1080))
        key = hashlib.sha1(f"{full}|{os.path.getmtime(full)}|{w}x{h}|{fit}".encode()).hexdigest()
        out = os.path.join(self.cache, "img", key[:2], key + ".jpg")
        if not os.path.exists(out):
            self.proxy.call("render_image", self.local_url(full), w, h, out, "cover" if fit == "cover" else "contain")
        return out

    def screensaver_photos(self):
        src = self.cfg["hub"].get("screensaver", {}).get("source", "gallery")
        if src == "wallpapers":
            return ["/api/wallpapers/" + quote(n) for n in self.list_wallpapers()] or ["/api/wallpaper.jpg"]
        if src == "bing":
            return ["/api/wallpaper.jpg?bing=1"]
        photos = self.library.photos("Gallery") or self.library.photos("Sent to TV")
        return ["/api/img?w=1280&h=720&fit=cover&src=" + quote(p) for p in photos] or ["/api/wallpaper.jpg"]

    # -- send to TV
    def show_on_tv(self, vpath):
        kind = Library.kind(vpath) or "photo"
        params = {"app": "player", "kind": kind, "src": vpath, "title": os.path.basename(vpath)}
        self.roku.open_hub(params)
        return {"ok": True, "kind": kind}

    def send_link(self, url):
        url = url.strip()
        m = re.search(r"(?:youtube\.com/(?:watch\?v=|shorts/|live/)|youtu\.be/)([0-9A-Za-z_-]{11})", url)
        if m:
            yt = self.roku.find_app("YouTube", "837")
            self.roku.launch(yt, {"contentId": m.group(1), "mediaType": "movie"})
            return {"ok": True, "opened": "youtube"}
        if "crunchyroll.com" in url:
            self.roku.launch(self.roku.find_app("Crunchyroll", "2595"))
            return {"ok": True, "opened": "crunchyroll"}
        ext = os.path.splitext(urlparse(url).path)[1].lower()
        if ext in VIDEO | AUDIO | {".m3u8"}:
            kind = "audio" if ext in AUDIO else "video"
            self.roku.open_hub({"app": "player", "kind": kind, "src": url, "title": os.path.basename(urlparse(url).path)})
            return {"ok": True, "opened": "player"}
        self.proxy.call("goto", url)
        self.roku.open_hub({"app": "browser"})
        return {"ok": True, "opened": "browser"}


class Internal:
    files = {}


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class FullHandler(Handler):
    def do_GET(self):
        p = urlparse(self.path).path
        if p.startswith("/internal/"):
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                self._err(403, "forbidden")
                return
            self._file(Internal.files.get(p.rsplit("/", 1)[1]))
            return
        super().do_GET()


def main():
    ap = argparse.ArgumentParser(description="Hub server for the Roku Hub channel.")
    ap.add_argument("--config", default=os.path.expanduser("~/.config/hub-server/config.json"))
    ap.add_argument("--tv", help="the TV's IP address (saved to the config)")
    ap.add_argument("--port", type=int, help="default 8765")
    args = ap.parse_args()

    cfg = Config(args.config)
    # --tv (TV_IP in the CasaOS app) only seeds the first run; after that the
    # address saved on the control page wins, so a stale TV_IP can't overwrite it.
    if args.tv and not cfg["tv_ip"]:
        cfg.update(tv_ip=args.tv)
    if args.port:
        cfg.update(port=args.port)
    if not cfg["tv_ip"]:
        log("Looking for the Roku TV on the network...")
        found = discover_roku()
        if found:
            log("Found Roku at", found)
            cfg.update(tv_ip=found)
        else:
            log("No Roku found yet - set the TV address on the control page.")

    data_dir = os.path.dirname(os.path.abspath(args.config))
    app = App(cfg, data_dir)
    Handler.S = app

    stop = threading.Event()
    threading.Thread(target=watch_tv, args=(app.roku, cfg, app.events, app.sleep, stop), daemon=True).start()

    server = ThreadingHTTPServer(("0.0.0.0", int(cfg["port"])), FullHandler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()

    app.browser = Browser(cfg, app.adblock, app.frames, int(cfg["port"]))
    ip = lan_ip()
    log(f"Hub server {VERSION} running. Control page: http://{ip}:{cfg['port']}")
    log(f"On the TV: Hub > Settings > Server address > {ip}")
    try:
        while True:
            if not app.proxy.run_pending(app.browser, 0.01):
                app.browser.pump(15)
            app.browser.tick(time.time() - app.frames.last_request < 12)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.shutdown()
        app.browser.close()


if __name__ == "__main__":
    main()
