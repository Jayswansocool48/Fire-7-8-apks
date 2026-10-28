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
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, quote_plus, unquote, urlencode, urlparse

from playwright.sync_api import Error as PWError
from playwright.sync_api import sync_playwright

try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9
    ZoneInfo = None

VERSION = "5.0.0"
# Where "Check for updates" looks for newer server files.
UPDATE_BASE = "https://raw.githubusercontent.com/Jayswansocool48/Fire-7-8-apks/main/hub-server/"
UPDATE_FILES = ["hub_server.py", "hub_control.html", "start.sh", "install.sh", "VERSION", "fonts/manrope.woff", "fonts/fraunces.woff",
                "fonts/fraunces-italic.woff", "fonts/LICENSE-Manrope-OFL.txt", "fonts/LICENSE-Fraunces-OFL.txt"]
W, H = 1280, 720  # the Roku app's canvas, so clicks map 1:1
HERE = os.path.dirname(os.path.abspath(__file__))
LAN = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never proxy TV traffic
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/130.0 Safari/537.36")

VIDEO = {".mp4", ".m4v", ".mkv", ".mov", ".ts", ".m2ts", ".avi", ".wmv", ".webm"}
AUDIO = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma"}
PHOTO = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic"}

DEFAULT_HUB = {
    "wallpaper": {"type": "preset", "value": "dusk"},
    "accent": "#D9894A",
    "clock24": False,
    "name": "",
    "units": "F",
    "location": "",
    # Home screen look: "hub" (rows of picks and apps) or "console" (game-console style).
    "theme": "hub",
    # Morning and night looks switch on by the clock: hours when each starts.
    "modes": {"auto": True, "morning": 5, "night": 21},
    # Rows on the home screen, top to bottom.
    "rows": [
        {"id": "continue", "on": True},
        {"id": "foryou", "on": True},
        {"id": "apps", "on": True},
        {"id": "myshows", "on": True},
        {"id": "youtube", "on": True},
        {"id": "anime", "on": True},
        {"id": "agenda", "on": True},
    ],
    # What the top of the home screen shows.
    "widgets": [
        {"id": "weather", "on": True},
        {"id": "note", "on": True},
        {"id": "quote", "on": True},
    ],
    "layout": 5,
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
    "quotes": [],
    "shows": [],
    "passed": [],  # titles you marked "Not for me"
    "history": [],  # what you opened from Hub, for Keep watching
    "profiles": [],  # people who use this TV; each keeps their own shows, history and look
    "profile": "",
    "recent_apps": [],  # channels in the order you last used them
    "tmdb_key": "",  # optional free TMDB key: better movie details and where-to-watch
    # Ask the TV: "ollama" (on your PC) or "claude" (API key). "share" sends the weather,
    # calendar and your saved shows along with questions; it stays off until you turn it on.
    "ai": {"provider": "ollama", "key": "", "model": "", "url": "", "share": False},
}

ROW_IDS = ["continue", "foryou", "apps", "myshows", "youtube", "anime", "agenda"]
# Accent colors offered on the control page and in Hub Remote.
ACCENTS = ["#D9894A", "#C9A44C", "#8FB38A", "#5FA3A0", "#C46F5E", "#7E97C9", "#C58A9B", "#B9A58F"]
WIDGET_IDS = ["weather", "note", "quote"]

# Wallpaper presets, as CSS. The server renders them to images for the TV; the control
# page shows the same CSS as thumbnails, so both always match.
PRESETS = {
    "dusk": "linear-gradient(170deg,#3a2f2a 0%,#6b4a3a 55%,#b7835a 100%)",
    "harbor": "linear-gradient(175deg,#1f2a2e 0%,#2f4a4f 60%,#56756f 100%)",
    "moss": "linear-gradient(165deg,#1c211b 0%,#36432f 60%,#6a7a52 100%)",
    "clay": "linear-gradient(160deg,#2a1d19 0%,#5a3529 60%,#9a5b43 100%)",
    "linen": "linear-gradient(180deg,#5e564b 0%,#8f8574 100%)",
    "slate": "linear-gradient(180deg,#1d2024 0%,#3a4048 100%)",
    "night": "linear-gradient(180deg,#121417 0%,#1f232b 100%)",
}

# Shown on the control page and in Hub Remote (Settings > Privacy and terms).
LEGAL = {
    "updated": "October 28, 2026",
    "privacy": [
        ["What Hub keeps", "Your settings, notes, drawings, quotes, saved shows, what you opened from Hub (Keep watching), profiles, wallpapers and the files you send to the TV. "
         "All of it stays on your server in the Hub folder (/DATA/AppData/hub-server on CasaOS). Hub has no accounts, "
         "no analytics, no ads and no cookies, and sends nothing to the person who made it."],
        ["Who Hub talks to", "Your Roku TV. Open-Meteo, with the place you typed, for the weather. The calendar link you added. "
         "YouTube and AniList, with the channels and anime you follow. TVmaze, AniList, Apple's iTunes Search and Wikidata, with show "
         "and movie titles, for details, cast, episodes and which services stream them; TMDB too if you add a TMDB key. Bing, for the daily photo. GitHub, for the ad block list and the update check. "
         "Any website you open in the TV browser sees that visit like any browser visit."],
        ["Ask the TV and AI picks", "Built-in answers (weather, time, calendar, apps, sleep timer, picks) never leave your server. "
         "Ollama runs on your own computer. If you choose Claude, your question goes to Anthropic, and with "
         "\"Share weather, calendar and shows\" on, so do the current weather, your next few calendar events and the titles "
         "of your saved shows. Anthropic handles that under its own privacy policy. Your API key stays on the server and "
         "never reaches the control page, the TV or the tablet."],
        ["Children", "Hub asks for no age, name or email, and has no sign-up."],
        ["Deleting your data", "Delete any note, show, quote or file from the control page or Hub Remote. "
         "Settings > Remove Hub deletes everything Hub keeps, including the server files. Your media folders are read-only to Hub "
         "and stay as they are."],
        ["Questions", "Hub is a personal project by Jayswansocool48 on GitHub, with no company behind it. "
         "Open an issue at github.com/Jayswansocool48/Fire-7-8-apks."],
    ],
    "terms": [
        ["Using Hub", "Hub is free. It has no fees, no subscription and nothing to refund. It comes as is, with no warranty."],
        ["What you send", "Send only photos, videos, drawings and links you have the right to show. They stay on your server, "
         "and you can delete them in Library or the notes list at any time."],
        ["Other services", "Roku, the streaming apps, Anthropic, Open-Meteo, TVmaze, Apple, AniList and YouTube each have their own "
         "terms. Hub opens their apps and pages; it doesn't change what they allow."],
        ["Fonts", "Hub bundles the Fraunces and Manrope fonts under the SIL Open Font License. The license files ship in the fonts folder."],
    ],
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
                self.data["hub"] = self._migrate(hub)
                self.data.pop("moods", None)
                for x in self.data.get("shows") or []:
                    x.pop("moods", None)
                self.data["ai"] = {**DEFAULTS["ai"], **(self.data.get("ai") or {})}
            except (OSError, ValueError) as e:
                log("Config unreadable, using defaults:", e)
        self.version = 1

    @staticmethod
    def _migrate(hub):
        """Hub 3 had widgets/apps/channels rows; Hub 4 puts apps and channels in the dock."""
        def fix(items, ids, defaults):
            items = [dict(i) for i in (items or []) if isinstance(i, dict) and i.get("id") in ids]
            have = {i["id"] for i in items}
            items += [dict(d) for d in defaults if d["id"] not in have]
            return items
        if hub.get("layout") not in (4, 5):
            # first run after Hub 3: new order, but keep YouTube/anime switched off if they were
            was = {r.get("id"): r.get("on", True) for r in hub.get("rows") or [] if isinstance(r, dict)}
            hub["rows"] = [{"id": d["id"], "on": was.get(d["id"], True)} for d in DEFAULT_HUB["rows"]]
            hub["widgets"] = [dict(d) for d in DEFAULT_HUB["widgets"]]
        hub["rows"] = fix(hub.get("rows"), ROW_IDS, DEFAULT_HUB["rows"])
        hub["widgets"] = fix(hub.get("widgets"), WIDGET_IDS, DEFAULT_HUB["widgets"])
        if hub.get("layout") == 4:  # Hub 5 adds Keep watching, For you and Apps at the top
            hub["rows"] = [{"id": i, "on": True} for i in ("continue", "foryou", "apps")] + \
                [r for r in hub["rows"] if r["id"] not in ("continue", "foryou", "apps")]
        hub["layout"] = 5
        if hub.get("theme") not in ("hub", "console"):
            hub["theme"] = "hub"
        m = hub.get("modes") if isinstance(hub.get("modes"), dict) else {}
        hub["modes"] = {"auto": m.get("auto", True) is not False,
                        "morning": min(12, max(3, int(m.get("morning", 5) or 5))),
                        "night": min(24, max(17, int(m.get("night", 21) or 21)))}
        if str(hub.get("accent", "")).upper() not in ACCENTS:
            hub["accent"] = DEFAULT_HUB["accent"]
        wall = hub.get("wallpaper") or {}
        if wall.get("type") == "preset" and wall.get("value") not in PRESETS:
            hub["wallpaper"] = dict(DEFAULT_HUB["wallpaper"])
        return hub

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
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(self.data, f, indent=2)
        os.chmod(tmp, 0o600)
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
        css = PRESETS.get(name, PRESETS["dusk"])
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

    def music(self, limit=800):
        """Every song in your media folders, newest folders first, for Music on the TV."""
        found = []
        for root_name, base in self.roots().items():
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames[:] = [d for d in dirnames if not d.startswith(".")]
                for f in sorted(filenames):
                    if os.path.splitext(f)[1].lower() in AUDIO:
                        full = os.path.join(dirpath, f)
                        rel = os.path.relpath(full, base).replace(os.sep, "/")
                        folder = os.path.basename(dirpath) if dirpath != base else root_name
                        try:
                            size = os.path.getsize(full)
                        except OSError:
                            size = 0
                        found.append({"name": os.path.splitext(f)[0], "path": root_name + "/" + rel, "kind": "audio",
                                      "size": size, "album": folder})
                if len(found) >= limit:
                    break
        return {"path": "::music", "dirs": [], "files": found[:limit]}

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
        d["notes"] = [{"kind": "text", "text": "", "color": "#FFD166", "created": 0, **n} for n in self.cfg["notes"][:20]]
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
               "&hourly=temperature_2m,precipitation_probability,weather_code"
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
                "wind": round(cur.get("wind_speed_10m") or 0), "unit": "F" if f else "C", "days": days,
                "tip": weather_tip(d, f)}

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


def _hour_label(iso):
    h = int(iso[11:13])
    return "midnight" if h == 0 else "noon" if h == 12 else f"{h % 12} {'AM' if h < 12 else 'PM'}"


def weather_tip(d, fahrenheit):
    """One line on what to wear or bring for the next 12 hours, like 'Light jacket, cools to 53° by 10 PM'."""
    try:
        hourly = d["hourly"]
        now = d["current"]["time"][:13]
        start = next(i for i, t in enumerate(hourly["time"]) if t[:13] >= now)
        idx = range(start, min(start + 12, len(hourly["time"])))
        temps = [(hourly["temperature_2m"][i], hourly["time"][i]) for i in idx]
        to_f = (lambda t: t) if fahrenheit else (lambda t: t * 9 / 5 + 32)
        for i in idx:
            code = hourly["weather_code"][i] or 0
            if (hourly["precipitation_probability"][i] or 0) >= 55:
                when = "now" if i == start else "around " + _hour_label(hourly["time"][i])
                if code in (71, 73, 75, 77, 85, 86):
                    return f"Snow {when}, dress warm"
                if code in (95, 96, 99):
                    return f"Storms {when}, stay in if you can"
                return f"Bring an umbrella, rain {when}"
        lo, lo_at = min(temps)
        hi, hi_at = max(temps)
        if to_f(lo) < 35:
            return f"Bundle up, down to {round(lo)}° by {_hour_label(lo_at)}"
        if to_f(lo) < 50:
            return f"Coat weather, {round(lo)}° by {_hour_label(lo_at)}"
        if to_f(lo) < 62:
            return f"Light jacket, cools to {round(lo)}° by {_hour_label(lo_at)}"
        if to_f(hi) > 86:
            return f"Stay cool, up to {round(hi)}° by {_hour_label(hi_at)}"
        return "T-shirt weather all day"
    except (KeyError, StopIteration, ValueError, TypeError):
        return ""


# ---------------------------------------------------------------- My Shows

# Streaming services as TVmaze / Apple name them -> words to find the matching Roku channel.
SERVICE_WORDS = [
    ("netflix", "netflix"), ("prime", "prime video"), ("amazon", "prime video"), ("disney", "disney"),
    ("hulu", "hulu"), ("max", "max"), ("hbo", "max"), ("peacock", "peacock"), ("paramount", "paramount"),
    ("apple", "apple tv"), ("crunchyroll", "crunchyroll"), ("youtube", "youtube"), ("tubi", "tubi"),
    ("pluto", "pluto"), ("starz", "starz"), ("showtime", "paramount"), ("fx", "hulu"), ("abc", "hulu"),
    ("nbc", "peacock"), ("cbs", "paramount"), ("adult swim", "max"), ("cartoon network", "max"),
]


class Shows:
    def __init__(self, cfg, roku, events):
        self.cfg = cfg
        self.roku = roku
        self.events = events

    # -- search TVmaze (TV) and Apple's catalog (movies); no keys needed
    def search(self, q):
        q = (q or "").strip()
        if not q:
            return []
        out = []
        try:
            for hit in fetch_json("https://api.tvmaze.com/search/shows?q=" + quote(q), timeout=12)[:8]:
                sh = hit["show"]
                net = (sh.get("webChannel") or {}).get("name") or (sh.get("network") or {}).get("name") or ""
                img = (sh.get("image") or {}).get("original") or (sh.get("image") or {}).get("medium")
                out.append({"key": f"tv:{sh['id']}", "title": sh["name"], "kind": "tv",
                            "year": (sh.get("premiered") or "")[:4], "poster": img, "network": net,
                            "tvmaze": sh["id"], "genres": norm_genres(sh.get("genres"))})
        except Exception as e:  # noqa: BLE001
            log("TVmaze search:", e)
        try:
            res = fetch_json("https://itunes.apple.com/search?media=movie&entity=movie&limit=8&term=" + quote(q), timeout=12)
            for m in res.get("results", []):
                art = (m.get("artworkUrl100") or "").replace("100x100bb", "600x900bb")
                out.append({"key": f"movie:{m.get('trackId')}", "title": m.get("trackName"), "kind": "movie",
                            "year": (m.get("releaseDate") or "")[:4], "poster": art, "network": "",
                            "genres": norm_genres([m.get("primaryGenreName", "")])})
        except Exception as e:  # noqa: BLE001
            log("Movie search:", e)
        return out

    def _backdrop(self, tvmaze_id):
        try:
            imgs = fetch_json(f"https://api.tvmaze.com/shows/{int(tvmaze_id)}/images", timeout=12)
            bgs = [i for i in imgs if i.get("type") == "background"]
            bgs.sort(key=lambda i: -(i.get("resolutions", {}).get("original", {}).get("width") or 0))
            if bgs:
                return bgs[0]["resolutions"]["original"]["url"]
        except Exception as e:  # noqa: BLE001
            log("TVmaze images:", e)
        return None

    def guess_app(self, network):
        n = (network or "").lower()
        if not n:
            return None
        try:
            apps = self.roku.apps()
        except Exception:  # noqa: BLE001
            return None
        for word, name in SERVICE_WORDS:
            if word in n:
                for a in apps:
                    if name in a["name"].lower():
                        return a
        for a in apps:
            if a["name"].lower() in n or n in a["name"].lower():
                return a
        return None

    def add(self, b):
        title = str(b.get("title", "")).strip()[:120]
        if not title:
            raise ValueError("Give the show a title")
        item = {"id": new_id(), "title": title, "kind": b.get("kind") if b.get("kind") in ("tv", "movie") else "tv",
                "year": str(b.get("year") or "")[:4], "poster": b.get("poster") or "", "backdrop": b.get("backdrop") or "",
                "app": b.get("app") or "", "appName": b.get("appName") or "",
                "genres": norm_genres(b.get("genres")), "network": b.get("network") or ""}
        if b.get("tvmaze") and not item["backdrop"]:
            item["backdrop"] = self._backdrop(b["tvmaze"]) or ""
        if not item["app"]:
            a = self.guess_app(item["network"])
            if a:
                item["app"], item["appName"] = a["id"], a["name"]
        if not item["genres"]:
            c = Recommender.CATALOG_KEYS.get(title_key(item["kind"], title))
            item["genres"] = list(c["genres"]) if c else []
        self.cfg.update(shows=[item] + [x for x in self.cfg["shows"] if x["title"].lower() != title.lower()])
        self.events.publish(type="widgets")
        return item

    def update(self, b):
        shows = self.cfg["shows"]
        for x in shows:
            if x["id"] == b.get("id"):
                if "app" in b:
                    x["app"] = b.get("app") or ""
                    x["appName"] = b.get("appName") or ""
        self.cfg.update(shows=shows)
        self.events.publish(type="widgets")

    def delete(self, show_id):
        self.cfg.update(shows=[x for x in self.cfg["shows"] if x["id"] != show_id])
        self.events.publish(type="widgets")

    def play(self, show):
        """Opens the title in its app through Roku search (works for any app that supports it)."""
        params = {"keyword": show["title"], "title": show["title"], "type": "movie" if show.get("kind") == "movie" else "tv-show"}
        if show.get("app"):
            params["provider-id"] = show["app"]
            params["launch"] = "true"
        self.roku._req("/search/browse?" + urlencode(params), "POST")
        return {"ok": True, "app": show.get("appName") or ""}

    def find(self, show_id):
        return next((x for x in self.cfg["shows"] if x["id"] == show_id), None)


# ---------------------------------------------------------------- recommendations

# Hub's own short list of shows and movies to suggest from: title|year|kind|genres.
# Picks lean toward the genres of the shows you saved. With an AI set up and sharing on,
# Claude or Ollama suggests titles too.
CATALOG = """
The Office|2005|tv|Comedy
Parks and Recreation|2009|tv|Comedy
Brooklyn Nine-Nine|2013|tv|Comedy,Crime
Abbott Elementary|2021|tv|Comedy
What We Do in the Shadows|2019|tv|Comedy,Horror
Schitt's Creek|2015|tv|Comedy
The Good Place|2016|tv|Comedy,Fantasy
Community|2009|tv|Comedy
Ted Lasso|2020|tv|Comedy,Drama,Sports
Only Murders in the Building|2021|tv|Comedy,Mystery,Crime
The Bear|2022|tv|Comedy,Drama
Arrested Development|2003|tv|Comedy
It's Always Sunny in Philadelphia|2005|tv|Comedy
Superstore|2015|tv|Comedy
New Girl|2011|tv|Comedy,Romance
How I Met Your Mother|2005|tv|Comedy,Romance
Friends|1994|tv|Comedy,Romance
Seinfeld|1989|tv|Comedy
Hacks|2021|tv|Comedy,Drama
Veep|2012|tv|Comedy
Derry Girls|2018|tv|Comedy
Fleabag|2016|tv|Comedy,Drama
Reservation Dogs|2021|tv|Comedy,Drama
Shrinking|2023|tv|Comedy,Drama
Barry|2018|tv|Comedy,Crime,Drama
Atlanta|2016|tv|Comedy,Drama
Curb Your Enthusiasm|2000|tv|Comedy
The Simpsons|1989|tv|Animation,Comedy
Bob's Burgers|2011|tv|Animation,Comedy,Family
Futurama|1999|tv|Animation,Comedy,Sci-Fi
Rick and Morty|2013|tv|Animation,Comedy,Sci-Fi
BoJack Horseman|2014|tv|Animation,Comedy,Drama
Bluey|2018|tv|Animation,Family,Comedy
Gravity Falls|2012|tv|Animation,Mystery,Family,Comedy
Avatar: The Last Airbender|2005|tv|Animation,Fantasy,Adventure,Family
Arcane|2021|tv|Animation,Fantasy,Action,Drama
Invincible|2021|tv|Animation,Superhero,Action
The Boys|2019|tv|Superhero,Action,Comedy
Loki|2021|tv|Superhero,Sci-Fi,Adventure
WandaVision|2021|tv|Superhero,Mystery,Comedy
Breaking Bad|2008|tv|Crime,Drama,Thriller
Better Call Saul|2015|tv|Crime,Drama
The Sopranos|1999|tv|Crime,Drama
The Wire|2002|tv|Crime,Drama
Succession|2018|tv|Drama,Comedy
Mad Men|2007|tv|Drama
The Crown|2016|tv|Drama
This Is Us|2016|tv|Drama,Family,Romance
Friday Night Lights|2006|tv|Drama,Sports
The Last of Us|2023|tv|Drama,Horror,Sci-Fi
Yellowstone|2018|tv|Drama,Western
Ozark|2017|tv|Crime,Drama,Thriller
Peaky Blinders|2013|tv|Crime,Drama
Shogun|2024|tv|Drama,Adventure
The Queen's Gambit|2020|tv|Drama
Normal People|2020|tv|Romance,Drama
Bridgerton|2020|tv|Romance,Drama
Outlander|2014|tv|Romance,Fantasy,Drama
Grey's Anatomy|2005|tv|Drama,Romance
The Pitt|2025|tv|Drama
Beef|2023|tv|Comedy,Drama
Slow Horses|2022|tv|Thriller,Crime
Mindhunter|2017|tv|Crime,Thriller
True Detective|2014|tv|Crime,Mystery,Drama
Mare of Easttown|2021|tv|Crime,Mystery,Drama
Sherlock|2010|tv|Crime,Mystery
Squid Game|2021|tv|Thriller,Drama
Money Heist|2017|tv|Crime,Thriller,Action
Dark|2017|tv|Sci-Fi,Mystery,Thriller
Black Mirror|2011|tv|Sci-Fi,Thriller
Stranger Things|2016|tv|Sci-Fi,Horror,Mystery
Severance|2022|tv|Sci-Fi,Mystery,Drama
The Mandalorian|2019|tv|Sci-Fi,Action,Adventure
Andor|2022|tv|Sci-Fi,Thriller
Silo|2023|tv|Sci-Fi,Mystery
Fallout|2024|tv|Sci-Fi,Action,Comedy
The Expanse|2015|tv|Sci-Fi,Drama
Westworld|2016|tv|Sci-Fi,Mystery
Game of Thrones|2011|tv|Fantasy,Drama,Action
House of the Dragon|2022|tv|Fantasy,Drama
The Witcher|2019|tv|Fantasy,Action
Wednesday|2022|tv|Comedy,Mystery,Horror
The Haunting of Hill House|2018|tv|Horror,Drama
Midnight Mass|2021|tv|Horror,Drama
From|2022|tv|Horror,Mystery
The Walking Dead|2010|tv|Horror,Drama,Action
Yellowjackets|2021|tv|Horror,Thriller,Mystery
Lost|2004|tv|Mystery,Sci-Fi,Adventure
The Leftovers|2014|tv|Drama,Mystery
Twin Peaks|1990|tv|Mystery,Drama
Doctor Who|2005|tv|Sci-Fi,Adventure
Star Trek: Strange New Worlds|2022|tv|Sci-Fi,Adventure
Reacher|2022|tv|Action,Crime
Jack Ryan|2018|tv|Action,Thriller
The Night Agent|2023|tv|Thriller,Action
Lupin|2021|tv|Crime,Mystery
The Great British Baking Show|2010|tv|Reality
Nailed It!|2018|tv|Reality,Comedy
Survivor|2000|tv|Reality
Planet Earth|2006|tv|Documentary
Our Planet|2019|tv|Documentary
Formula 1: Drive to Survive|2019|tv|Documentary,Sports
Attack on Titan|2013|tv|Anime,Action,Fantasy
Demon Slayer: Kimetsu no Yaiba|2019|tv|Anime,Action,Fantasy
Jujutsu Kaisen|2020|tv|Anime,Action,Fantasy
One Piece|1999|tv|Anime,Adventure,Action
Frieren: Beyond Journey's End|2023|tv|Anime,Fantasy,Adventure
Spy x Family|2022|tv|Anime,Comedy,Action,Family
Chainsaw Man|2022|tv|Anime,Action,Horror
Fullmetal Alchemist: Brotherhood|2009|tv|Anime,Adventure,Fantasy
Death Note|2006|tv|Anime,Thriller,Mystery
Cowboy Bebop|1998|tv|Anime,Sci-Fi,Action
My Hero Academia|2016|tv|Anime,Superhero,Action
Hunter x Hunter|2011|tv|Anime,Adventure,Action
Haikyu!!|2014|tv|Anime,Sports,Comedy
Mob Psycho 100|2016|tv|Anime,Action,Comedy
Solo Leveling|2024|tv|Anime,Action,Fantasy
Dan Da Dan|2024|tv|Anime,Action,Comedy,Sci-Fi
Vinland Saga|2019|tv|Anime,Action,Drama
Steins;Gate|2011|tv|Anime,Sci-Fi,Thriller
Naruto Shippuden|2007|tv|Anime,Action,Adventure
Bocchi the Rock!|2022|tv|Anime,Comedy,Music
Kaguya-sama: Love Is War|2019|tv|Anime,Comedy,Romance
Violet Evergarden|2018|tv|Anime,Drama,Fantasy
Your Lie in April|2014|tv|Anime,Romance,Drama,Music
Blue Lock|2022|tv|Anime,Sports
Neon Genesis Evangelion|1995|tv|Anime,Sci-Fi,Drama
Oshi no Ko|2023|tv|Anime,Drama,Mystery
The Apothecary Diaries|2023|tv|Anime,Mystery,Drama
Kaiju No. 8|2024|tv|Anime,Action,Sci-Fi
Everything Everywhere All at Once|2022|movie|Sci-Fi,Comedy,Action
Knives Out|2019|movie|Mystery,Comedy,Crime
Glass Onion|2022|movie|Mystery,Comedy,Crime
Parasite|2019|movie|Thriller,Drama,Comedy
Get Out|2017|movie|Horror,Thriller,Mystery
The Grand Budapest Hotel|2014|movie|Comedy,Adventure
Spider-Man: Into the Spider-Verse|2018|movie|Animation,Superhero,Action
Spider-Man: Across the Spider-Verse|2023|movie|Animation,Superhero,Action
Dune|2021|movie|Sci-Fi,Adventure
Dune: Part Two|2024|movie|Sci-Fi,Adventure,Action
Interstellar|2014|movie|Sci-Fi,Drama,Adventure
Inception|2010|movie|Sci-Fi,Action,Thriller
The Dark Knight|2008|movie|Superhero,Action,Crime
Mad Max: Fury Road|2015|movie|Action,Sci-Fi,Adventure
John Wick|2014|movie|Action,Thriller,Crime
Top Gun: Maverick|2022|movie|Action,Drama
Mission: Impossible Fallout|2018|movie|Action,Thriller
The Social Network|2010|movie|Drama
Whiplash|2014|movie|Drama,Music
La La Land|2016|movie|Romance,Musical,Drama
Past Lives|2023|movie|Romance,Drama
About Time|2013|movie|Romance,Comedy,Fantasy
Crazy Rich Asians|2018|movie|Romance,Comedy
The Princess Bride|1987|movie|Fantasy,Adventure,Romance,Comedy
Back to the Future|1985|movie|Sci-Fi,Comedy,Adventure
Jurassic Park|1993|movie|Sci-Fi,Adventure
The Matrix|1999|movie|Sci-Fi,Action
Alien|1979|movie|Horror,Sci-Fi
Arrival|2016|movie|Sci-Fi,Drama,Mystery
Blade Runner 2049|2017|movie|Sci-Fi,Mystery
Edge of Tomorrow|2014|movie|Sci-Fi,Action
The Martian|2015|movie|Sci-Fi,Adventure,Drama
Spirited Away|2001|movie|Anime,Animation,Fantasy,Family
My Neighbor Totoro|1988|movie|Anime,Animation,Family,Fantasy
Howl's Moving Castle|2004|movie|Anime,Animation,Fantasy,Romance
Princess Mononoke|1997|movie|Anime,Animation,Fantasy,Adventure
Your Name.|2016|movie|Anime,Animation,Romance,Fantasy
Suzume|2022|movie|Anime,Animation,Adventure,Fantasy
The Boy and the Heron|2023|movie|Anime,Animation,Fantasy
Akira|1988|movie|Anime,Animation,Sci-Fi,Action
Demon Slayer: Mugen Train|2020|movie|Anime,Animation,Action,Fantasy
Jujutsu Kaisen 0|2021|movie|Anime,Animation,Action,Fantasy
A Silent Voice|2016|movie|Anime,Animation,Drama,Romance
Perfect Blue|1997|movie|Anime,Animation,Thriller,Horror
Coco|2017|movie|Animation,Family,Fantasy,Music
Inside Out|2015|movie|Animation,Family,Comedy
Inside Out 2|2024|movie|Animation,Family,Comedy
Up|2009|movie|Animation,Family,Adventure
Toy Story|1995|movie|Animation,Family,Comedy
WALL-E|2008|movie|Animation,Sci-Fi,Family
Ratatouille|2007|movie|Animation,Comedy,Family
The Incredibles|2004|movie|Animation,Superhero,Family,Action
Shrek|2001|movie|Animation,Comedy,Fantasy,Family
The Lego Movie|2014|movie|Animation,Comedy,Family
Puss in Boots: The Last Wish|2022|movie|Animation,Adventure,Comedy,Family
The Mitchells vs. the Machines|2021|movie|Animation,Comedy,Sci-Fi,Family
Paddington 2|2017|movie|Family,Comedy,Adventure
Klaus|2019|movie|Animation,Family,Comedy
Encanto|2021|movie|Animation,Musical,Family
Moana|2016|movie|Animation,Adventure,Musical,Family
Soul|2020|movie|Animation,Family,Comedy,Music
Kung Fu Panda|2008|movie|Animation,Action,Comedy,Family
How to Train Your Dragon|2010|movie|Animation,Fantasy,Adventure,Family
The Iron Giant|1999|movie|Animation,Sci-Fi,Family
Nimona|2023|movie|Animation,Fantasy,Comedy,Action
The Super Mario Bros. Movie|2023|movie|Animation,Adventure,Comedy,Family
Wicked|2024|movie|Musical,Fantasy
Barbie|2023|movie|Comedy,Fantasy
Oppenheimer|2023|movie|Drama,Thriller
Avengers: Endgame|2019|movie|Superhero,Action,Sci-Fi
Avengers: Infinity War|2018|movie|Superhero,Action,Sci-Fi
Guardians of the Galaxy|2014|movie|Superhero,Sci-Fi,Comedy,Action
Black Panther|2018|movie|Superhero,Action
Deadpool & Wolverine|2024|movie|Superhero,Comedy,Action
Thor: Ragnarok|2017|movie|Superhero,Comedy,Action
The Batman|2022|movie|Superhero,Crime,Mystery
Logan|2017|movie|Superhero,Action,Drama
A Quiet Place|2018|movie|Horror,Sci-Fi,Thriller
Hereditary|2018|movie|Horror,Mystery
The Conjuring|2013|movie|Horror
Talk to Me|2023|movie|Horror
Nope|2022|movie|Horror,Sci-Fi,Mystery
Scream|1996|movie|Horror,Mystery
It|2017|movie|Horror
Barbarian|2022|movie|Horror,Thriller
Sinners|2025|movie|Horror,Drama
The Shining|1980|movie|Horror
Gone Girl|2014|movie|Thriller,Mystery,Crime
Prisoners|2013|movie|Thriller,Crime,Mystery
Zodiac|2007|movie|Crime,Mystery,Thriller
Se7en|1995|movie|Crime,Thriller,Mystery
The Silence of the Lambs|1991|movie|Thriller,Crime,Horror
Heat|1995|movie|Crime,Action,Thriller
The Godfather|1972|movie|Crime,Drama
Goodfellas|1990|movie|Crime,Drama
Pulp Fiction|1994|movie|Crime,Comedy
Baby Driver|2017|movie|Action,Crime,Music
The Nice Guys|2016|movie|Comedy,Crime,Action
Hot Fuzz|2007|movie|Comedy,Action,Crime,Mystery
Shaun of the Dead|2004|movie|Comedy,Horror
Superbad|2007|movie|Comedy
Bridesmaids|2011|movie|Comedy
Game Night|2018|movie|Comedy,Crime,Mystery
Palm Springs|2020|movie|Comedy,Romance,Sci-Fi
The Holdovers|2023|movie|Comedy,Drama
Booksmart|2019|movie|Comedy
Free Guy|2021|movie|Comedy,Action,Sci-Fi
Jumanji: Welcome to the Jungle|2017|movie|Adventure,Comedy,Action,Family
Ferris Bueller's Day Off|1986|movie|Comedy
Groundhog Day|1993|movie|Comedy,Romance,Fantasy
Mean Girls|2004|movie|Comedy
The Truman Show|1998|movie|Comedy,Drama,Sci-Fi
Little Women|2019|movie|Drama,Romance
Pride & Prejudice|2005|movie|Romance,Drama
When Harry Met Sally...|1989|movie|Romance,Comedy
10 Things I Hate About You|1999|movie|Romance,Comedy
Anyone but You|2023|movie|Romance,Comedy
Gladiator|2000|movie|Action,Drama,Adventure
Raiders of the Lost Ark|1981|movie|Adventure,Action
Pirates of the Caribbean: The Curse of the Black Pearl|2003|movie|Adventure,Fantasy,Action
The Lord of the Rings: The Fellowship of the Ring|2001|movie|Fantasy,Adventure
Harry Potter and the Sorcerer's Stone|2001|movie|Fantasy,Family,Adventure
Star Wars|1977|movie|Sci-Fi,Adventure,Action
Dungeons & Dragons: Honor Among Thieves|2023|movie|Fantasy,Adventure,Comedy
Ford v Ferrari|2019|movie|Drama,Sports,Action
Creed|2015|movie|Drama,Sports
Moneyball|2011|movie|Drama,Sports
Hidden Figures|2016|movie|Drama
The Shawshank Redemption|1994|movie|Drama,Crime
Good Will Hunting|1997|movie|Drama
Godzilla Minus One|2023|movie|Sci-Fi,Action,Drama
RRR|2022|movie|Action,Drama
Crouching Tiger, Hidden Dragon|2000|movie|Action,Fantasy,Romance
Free Solo|2018|movie|Documentary,Sports
"""

GENRES = ["Comedy", "Drama", "Crime", "Thriller", "Mystery", "Sci-Fi", "Fantasy", "Horror", "Action", "Adventure",
          "Romance", "Animation", "Anime", "Family", "Superhero", "Documentary", "Reality", "Sports", "Music",
          "Musical", "Western"]

# Words people use when they ask for something -> catalog genres.
GENRE_WORDS = {
    "funny": "Comedy", "comedy": "Comedy", "laugh": "Comedy", "scary": "Horror", "horror": "Horror",
    "creepy": "Horror", "romantic": "Romance", "romance": "Romance", "date night": "Romance", "love": "Romance",
    "action": "Action", "anime": "Anime", "cartoon": "Animation", "animated": "Animation", "kids": "Family",
    "family": "Family", "sci-fi": "Sci-Fi", "scifi": "Sci-Fi", "space": "Sci-Fi", "fantasy": "Fantasy",
    "mystery": "Mystery", "detective": "Mystery", "crime": "Crime", "thriller": "Thriller", "intense": "Thriller",
    "drama": "Drama", "superhero": "Superhero", "documentary": "Documentary", "sports": "Sports",
    "adventure": "Adventure", "musical": "Musical",
}

# TVmaze and Apple genre names -> catalog genres.
GENRE_ALIASES = {"science-fiction": "Sci-Fi", "sci-fi & fantasy": "Sci-Fi", "kids & family": "Family",
                 "children": "Family", "music": "Music", "anime": "Anime", "supernatural": "Fantasy",
                 "war": "Drama", "history": "Drama", "legal": "Crime", "medical": "Drama", "espionage": "Thriller",
                 "suspense": "Thriller", "comedies": "Comedy", "action & adventure": "Action",
                 "documentaries": "Documentary", "romance": "Romance", "musicals": "Musical",
                 "horror": "Horror", "western": "Western", "sports": "Sports", "food": "Reality",
                 "travel": "Documentary", "nature": "Documentary"}


def norm_genres(names):
    out = []
    for n in names or []:
        n = str(n).strip()
        g = GENRE_ALIASES.get(n.lower()) or next((x for x in GENRES if x.lower() == n.lower()), None)
        if g and g not in out:
            out.append(g)
    return out


def title_key(kind, title, year=""):
    return f"{'movie' if kind == 'movie' else 'tv'}:{re.sub(r'[^a-z0-9]+', ' ', (title or '').lower()).strip()}:{str(year or '')[:4]}"


def base_key(key):
    """A title key without its year, so a saved show matches the catalog's copy of it."""
    return key.rsplit(":", 1)[0]


def strip_html(text):
    return html.unescape(re.sub(r"<[^>]+>", "", text or "")).strip()


def short_summary(text, limit=230):
    text = re.sub(r"\s+", " ", strip_html(text))
    if len(text) <= limit:
        return text
    cut = text[:limit]
    end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    return cut[:end + 1] if end > 80 else cut.rsplit(" ", 1)[0] + "…"


class Recommender:
    """Suggests shows and movies. Each pick says which saved show it follows from."""
    CATALOG_KEYS = {}

    def __init__(self, app):
        self.app = app
        self.lock = threading.Lock()
        self.cache_path = os.path.join(app.cache, "titles.json")
        try:
            with open(self.cache_path) as f:
                self.titles = json.load(f)
        except (OSError, ValueError):
            self.titles = {}
        self.catalog = []
        for line in CATALOG.strip().splitlines():
            t, y, k, g = line.split("|")
            self.catalog.append({"title": t, "year": y, "kind": k, "genres": g.split(",")})
        self.by_key = {title_key(c["kind"], c["title"]): c for c in self.catalog}
        Recommender.CATALOG_KEYS = self.by_key
        self.recent = []      # keys shown lately, so "Another" moves on
        self.ai_pool = {}     # kind -> (time, [items]) from the AI
        self.top = None       # the current first pick, for the TV home card
        self.feed = []        # the For you row on the TV

    # -- what you like
    def _genres_of(self, show):
        if show.get("genres"):
            return norm_genres(show["genres"])
        c = self.by_key.get(title_key(show.get("kind"), show.get("title")))
        return c["genres"] if c else []

    def taste(self):
        weights, sources = {}, {}
        for s in self.app.cfg["shows"]:
            for g in self._genres_of(s):
                weights[g] = weights.get(g, 0) + 1
                sources.setdefault(g, []).append(s["title"])
        return weights, sources

    # -- choosing
    def _skip_keys(self):
        cfg = self.app.cfg
        keys = {base_key(title_key(s.get("kind"), s.get("title"))) for s in cfg["shows"]}
        keys |= {base_key(k) for k in cfg.get("passed", [])}
        return keys

    def _score(self, item, weights, genre):
        gs = item["genres"]
        if genre and genre not in gs:
            return -1
        base = sum(weights.get(g, 0) for g in gs) / max(1.0, len(gs) ** 0.5)
        return base + random.random() * (1.2 if weights else 3.0)

    def _reason(self, item, weights, sources, genre):
        if item.get("reason"):
            return item["reason"]
        shared = sorted(item["genres"], key=lambda g: -weights.get(g, 0))
        for g in shared:
            if sources.get(g):
                names = [n for n in sources[g] if n.lower() != item["title"].lower()]
                if names:
                    return f"Because you saved {random.choice(names[:3])}"
        what = "movie" if item["kind"] == "movie" else "show"
        g = genre or (item["genres"][0] if item["genres"] else "")
        return f"A {g.lower()} {what} to start with" if g else f"A {what} to start with"

    def pick(self, kind="any", genre=None, n=6, fresh=False):
        kind = kind if kind in ("tv", "movie") else "any"
        genre = genre if genre in GENRES else None
        weights, sources = self.taste()
        skip = self._skip_keys()
        recent = set(self.recent[-40:]) if not fresh else set()
        pool = []
        for item in self._ai_items(kind) + self.catalog:
            key = base_key(title_key(item["kind"], item["title"]))
            if key in skip or key in recent or (kind != "any" and item["kind"] != kind):
                continue
            if any(title_key(p["kind"], p["title"]) == key for p in pool):
                continue
            pool.append(item)
        if not pool:  # seen everything lately: start the rotation over
            self.recent = []
            pool = [c for c in self.catalog if base_key(title_key(c["kind"], c["title"])) not in skip
                    and (kind == "any" or c["kind"] == kind)]
        scored = sorted(((self._score(i, weights, genre) + (2.5 if i.get("reason") else 0), i) for i in pool),
                        key=lambda x: -x[0])
        chosen = [i for s, i in scored if s >= 0][:n]
        if not chosen and genre:
            return self.pick(kind, None, n, fresh)
        with ThreadPoolExecutor(max_workers=6) as ex:
            out = list(ex.map(lambda i: self.describe(i, self._reason(i, weights, sources, genre)), chosen))
        out = [o for o in out if o]
        self.recent += [o["key"].rsplit(":", 1)[0] for o in out[:1]]
        if out:
            self.top = out[0]
        return out

    def refresh_soon(self):
        """Rebuilds the For you row in the background (after a profile switch or a new show)."""
        def run():
            try:
                self.feed = self.pick("any", None, n=12)
                self.app.events.publish(type="widgets")
            except Exception as e:  # noqa: BLE001
                log("Pick refresh:", e)
        threading.Thread(target=run, daemon=True).start()

    def warm(self):
        """Keeps a fresh pick ready for the TV home screen."""
        while True:
            try:
                had = (self.top or {}).get("key")
                self.feed = self.pick("any", None, n=12)
                if (self.top or {}).get("key") != had:
                    self.app.events.publish(type="widgets")
            except Exception as e:  # noqa: BLE001
                log("Pick refresh:", e)
            time.sleep(3 * 3600)

    def note_seen(self, key):
        k = key.rsplit(":", 1)[0]
        if k not in self.recent:
            self.recent.append(k)

    def passed(self, key, undo=False):
        cfg = self.app.cfg
        keys = [k for k in cfg.get("passed", []) if k != key]
        if not undo:
            keys = (keys + [key])[-300:]
        cfg.update(passed=keys)

    # -- the AI's suggestions (only when sharing is on)
    def _ai_items(self, kind):
        ai = self.app.cfg["ai"] or {}
        if not ai.get("share") or not ai.get("provider"):
            return []
        got = self.ai_pool.get(kind)
        if got and time.time() - got[0] < 1800:
            return got[1]
        items = []
        saved = [s["title"] for s in self.app.cfg["shows"]][:40]
        if saved:
            what = {"tv": "TV shows", "movie": "movies"}.get(kind, "TV shows or movies")
            q = ("Suggest 8 " + what + " for someone who saved these: " + "; ".join(saved) + ". "
                 "Skip anything on that list. Reply with one per line, exactly like: "
                 "Title | year | show or movie | one short reason, under 12 words, that names a saved title")
            text = self.app.assistant.raw(q, max_tokens=500)
            for line in (text or "").splitlines():
                parts = [p.strip(" -*•\t") for p in line.split("|")]
                if len(parts) < 4 or not parts[0]:
                    continue
                k = "movie" if "movie" in parts[2].lower() else "tv"
                if kind != "any" and k != kind:
                    continue
                c = self.by_key.get(title_key(k, parts[0]))
                items.append({"title": parts[0][:120], "year": re.sub(r"\D", "", parts[1])[:4], "kind": k,
                              "genres": c["genres"] if c else [], "reason": parts[3][:90]})
        self.ai_pool[kind] = (time.time(), items)
        return items

    # -- poster, summary, where to watch (cached)
    def describe(self, item, reason):
        ck = title_key(item["kind"], item["title"], item.get("year"))
        info = self.titles.get(ck)
        if not info:
            info = self._lookup(item) or {}
        if info and ck not in self.titles:
            with self.lock:
                self.titles[ck] = info
                try:
                    tmp = self.cache_path + ".tmp"
                    with open(tmp, "w") as f:
                        json.dump(self.titles, f)
                    os.replace(tmp, self.cache_path)
                except OSError:
                    pass
        out = {"key": ck, "title": item["title"], "year": item.get("year") or info.get("year", ""),
               "kind": item["kind"], "genres": item["genres"] or norm_genres(info.get("genres")),
               "summary": info.get("summary", ""), "poster": info.get("poster", ""), "backdrop": info.get("backdrop", ""),
               "network": info.get("network", ""), "tvmaze": info.get("tvmaze"), "reason": reason, "app": "", "appName": ""}
        a = self.app.shows.guess_app(out["network"]) if out["network"] else None
        if a:
            out["app"], out["appName"] = a["id"], a["name"]
        return out

    def _lookup(self, item):
        try:
            if item["kind"] == "tv":
                hits = [h["show"] for h in fetch_json("https://api.tvmaze.com/search/shows?q=" + quote(item["title"]), timeout=10)[:6]]
                y = str(item.get("year") or "")
                sh = next((h for h in hits if y and (h.get("premiered") or "").startswith(y)), None)
                sh = sh or next((h for h in hits if h.get("name", "").lower() == item["title"].lower()), None) or (hits[0] if hits else None)
                if not sh:
                    return None
                img = sh.get("image") or {}
                return {"title": sh.get("name"), "year": (sh.get("premiered") or "")[:4], "summary": short_summary(sh.get("summary")),
                        "poster": img.get("original") or img.get("medium") or "", "genres": sh.get("genres") or [],
                        "network": (sh.get("webChannel") or {}).get("name") or (sh.get("network") or {}).get("name") or "",
                        "backdrop": self.app.shows._backdrop(sh["id"]) or "", "tvmaze": sh["id"]}
            res = fetch_json("https://itunes.apple.com/search?media=movie&entity=movie&limit=6&term=" + quote(item["title"]), timeout=10)
            hits = res.get("results", [])
            y = str(item.get("year") or "")
            m = next((h for h in hits if y and (h.get("releaseDate") or "").startswith(y)), hits[0] if hits else None)
            if not m:
                return None
            art = (m.get("artworkUrl100") or "").replace("100x100bb", "600x900bb")
            return {"title": m.get("trackName"), "year": (m.get("releaseDate") or "")[:4],
                    "summary": short_summary(m.get("longDescription") or m.get("shortDescription")),
                    "poster": art, "genres": [m.get("primaryGenreName", "")], "network": "", "backdrop": ""}
        except Exception as e:  # noqa: BLE001
            log("Title lookup:", item["title"], e)
            return None


# ---------------------------------------------------------------- title details and where to watch

# Streaming services Hub knows how to deep link into. "words" match the Roku app's name;
# "wd" are the Wikidata properties that hold that service's own ID for a title.
PROVIDERS = [
    {"name": "Netflix", "words": ["netflix"], "wd": {"tv": ["P1874"], "movie": ["P1874"]}},
    {"name": "Crunchyroll", "words": ["crunchyroll"], "wd": {"tv": ["P11330"], "movie": ["P11330"]}},
    {"name": "Hulu", "words": ["hulu"], "wd": {"tv": ["P6467"], "movie": ["P6466"]}},
    {"name": "Disney+", "words": ["disney"], "wd": {"tv": ["P7596"], "movie": ["P7595"]}},
    {"name": "Max", "words": ["max", "hbo"], "wd": {"tv": ["P8298"], "movie": ["P8298"]}},
    {"name": "Prime Video", "words": ["prime video", "amazon"], "wd": {"tv": ["P8055", "P14440"], "movie": ["P8055", "P14440"]}},
    {"name": "Peacock", "words": ["peacock"], "wd": {}},
    {"name": "Paramount+", "words": ["paramount"], "wd": {}},
    {"name": "Apple TV", "words": ["apple tv"], "wd": {}},
    {"name": "YouTube", "words": ["youtube"], "wd": {}},
    {"name": "Tubi", "words": ["tubi"], "wd": {}},
    {"name": "Pluto TV", "words": ["pluto"], "wd": {}},
    {"name": "HIDIVE", "words": ["hidive"], "wd": {}},
]
# Network names on TVmaze and AniList link sites -> the service that streams them.
NETWORK_TO_PROVIDER = {
    "netflix": "Netflix", "crunchyroll": "Crunchyroll", "hulu": "Hulu", "disney+": "Disney+", "disney plus": "Disney+",
    "hbo": "Max", "hbo max": "Max", "max": "Max", "prime video": "Prime Video", "amazon": "Prime Video",
    "peacock": "Peacock", "paramount+": "Paramount+", "apple tv+": "Apple TV", "apple tv": "Apple TV",
    "youtube": "YouTube", "tubi": "Tubi", "pluto tv": "Pluto TV", "hidive": "HIDIVE", "fx": "Hulu", "adult swim": "Max",
    "nbc": "Peacock", "cbs": "Paramount+", "abc": "Hulu", "amc": "AMC+", "showtime": "Paramount+",
}
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.I)
ANILIST = "https://graphql.anilist.co"
ANILIST_Q = """query ($s: String, $id: Int) { Media(search: $s, id: $id, type: ANIME) {
  id title { english romaji } description(asHtml: false) episodes duration status seasonYear format averageScore genres
  studios(isMain: true) { nodes { name } } coverImage { extraLarge } bannerImage
  nextAiringEpisode { episode airingAt }
  externalLinks { site url type }
  streamingEpisodes { title thumbnail url site }
  characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { role node { name { full } image { medium } } } }
  recommendations(perPage: 8, sort: RATING_DESC) { nodes { mediaRecommendation { id title { english romaji } coverImage { extraLarge } seasonYear } } }
} }"""


def provider_named(name):
    n = (name or "").strip().lower()
    for p in PROVIDERS:
        if p["name"].lower() == n:
            return p
    return None


def content_id(provider, value):
    """The ID a Roku app expects for a deep link, from the service's own ID or page URL."""
    v = str(value or "").strip()
    if not v:
        return ""
    if provider == "Netflix":
        m = re.search(r"(\d{6,})", v)
        return m.group(1) if m else ""
    if provider == "Crunchyroll":
        m = re.search(r"/(?:series|watch)/([A-Z0-9]{6,})", v) or re.fullmatch(r"([A-Z0-9]{6,})", v)
        return m.group(1) if m else ""
    if provider in ("Hulu", "Max"):
        m = UUID_RE.search(v)
        return m.group(0) if m else v.rsplit("/", 1)[-1]
    if provider == "Disney+":
        return v.rstrip("/").rsplit("/", 1)[-1]
    if provider == "Prime Video":
        m = re.search(r"/detail/(?:[^/]+/)?([A-Z0-9]{10,})", v) or re.fullmatch(r"([A-Z0-9.\-]{10,})", v)
        return m.group(1) if m else ""
    return ""


class Titles:
    """Full details for a show or movie, which of your channels have it, and opening it
    straight to that title. Sources: TVmaze, AniList, Apple's catalog and Wikidata (no keys),
    plus TMDB when you add a free TMDB key."""

    def __init__(self, app):
        self.app = app
        self.lock = threading.Lock()
        self.path = os.path.join(app.cache, "details.json")
        try:
            with open(self.path) as f:
                self.cache = json.load(f)
        except (OSError, ValueError):
            self.cache = {}

    def _save(self):
        with self.lock:
            try:
                tmp = self.path + ".tmp"
                with open(tmp, "w") as f:
                    json.dump(self.cache, f)
                os.replace(tmp, self.path)
            except OSError:
                pass

    # -- the Roku apps you have, matched to services
    def installed(self):
        try:
            apps = self.app.roku.apps()
        except Exception:  # noqa: BLE001
            apps = []
        out = {}
        for p in PROVIDERS:
            for a in apps:
                n = a["name"].lower()
                if any(w == n or w in n for w in p["words"]) and not (p["name"] == "Max" and "maxx" in n):
                    out[p["name"]] = a
                    break
        return out

    # -- details
    def details(self, q, fresh=False):
        kind = "movie" if q.get("kind") == "movie" else "tv"
        title = str(q.get("title") or "").strip()
        year = str(q.get("year") or "")[:4]
        ck = q.get("key") or title_key(kind, title, year)
        hit = self.cache.get(ck)
        if hit and not fresh and time.time() - hit.get("_at", 0) < 3 * 86400:
            return self._with_apps(hit)
        info = {"key": ck, "kind": kind, "title": title, "year": year, "genres": [], "summary": "", "poster": "",
                "backdrop": "", "rating": None, "runtime": None, "status": "", "network": "", "cast": [],
                "seasons": [], "episodes": [], "similar": [], "providers": {}, "ids": {}, "anime": False}
        anime = q.get("anime") in (True, "1", "true") or "Anime" in (q.get("genres") or []) or bool(q.get("anilist"))
        def step(fn, *args):
            try:
                fn(info, *args)
            except Exception as e:  # noqa: BLE001  one source failing shouldn't lose the others
                log("Details:", title, fn.__name__, e)
        if kind == "tv" and not anime:
            step(self._tvmaze, q.get("tvmaze"))
            anime = anime or "Anime" in norm_genres(info["genres"]) or bool(info.get("_jp"))
        if anime:
            step(self._anilist, q.get("anilist"))
        if kind == "movie" and not anime:
            step(self._itunes, q.get("itunes"))
        step(self._wikidata)
        step(self._tmdb)
        if not info["title"]:
            raise ValueError("Couldn't find that title")
        info["genres"] = norm_genres(info["genres"]) or info["genres"][:4]
        if info["anime"] and "Anime" not in info["genres"]:
            info["genres"].insert(0, "Anime")
        info["_at"] = time.time()
        info.pop("_jp", None)
        if info["summary"] or info["poster"] or info["providers"]:  # don't keep a lookup that failed
            self.cache[ck] = info
            self._save()
        return self._with_apps(info)

    def _add(self, info, name, value="", url="", source=""):
        if not name:
            return
        p = info["providers"].setdefault(name, {"name": name, "contentId": "", "url": "", "source": source})
        cid = content_id(name, value) or content_id(name, url)
        if cid and not p["contentId"]:
            p["contentId"] = cid
        if url and not p["url"]:
            p["url"] = url

    def _tvmaze(self, info, tvmaze_id):
        if tvmaze_id:
            sh = fetch_json(f"https://api.tvmaze.com/shows/{int(tvmaze_id)}?embed[]=cast&embed[]=seasons", timeout=12)
        else:
            sh = fetch_json("https://api.tvmaze.com/singlesearch/shows?embed[]=cast&embed[]=seasons&q=" + quote(info["title"]), timeout=12)
        if not sh:
            return
        img = sh.get("image") or {}
        net = (sh.get("webChannel") or {}).get("name") or (sh.get("network") or {}).get("name") or ""
        info.update(title=sh.get("name") or info["title"], year=(sh.get("premiered") or "")[:4] or info["year"],
                    summary=strip_html(sh.get("summary")), poster=img.get("original") or img.get("medium") or "",
                    genres=sh.get("genres") or [], rating=(sh.get("rating") or {}).get("average"),
                    runtime=sh.get("runtime") or sh.get("averageRuntime"), status=sh.get("status") or "", network=net)
        info["ids"].update(tvmaze=sh["id"], imdb=(sh.get("externals") or {}).get("imdb"))
        info["_jp"] = (sh.get("language") == "Japanese" and sh.get("type") == "Animation")
        emb = sh.get("_embedded") or {}
        info["cast"] = [{"name": c["person"]["name"], "role": (c.get("character") or {}).get("name", ""),
                         "photo": ((c["person"].get("image") or {}).get("medium") or "")} for c in (emb.get("cast") or [])[:12]]
        info["seasons"] = [{"id": s["id"], "number": s.get("number"), "episodes": s.get("episodeOrder"),
                            "year": (s.get("premiereDate") or "")[:4]} for s in emb.get("seasons") or [] if s.get("number")]
        info["backdrop"] = self.app.shows._backdrop(sh["id"]) or ""
        prov = NETWORK_TO_PROVIDER.get(net.lower())
        if prov:
            self._add(info, prov, source="network")

    def episodes(self, season_id):
        eps = fetch_json(f"https://api.tvmaze.com/seasons/{int(season_id)}/episodes", timeout=12)
        return [{"number": e.get("number"), "name": e.get("name") or f"Episode {e.get('number')}",
                 "airdate": e.get("airdate") or "", "runtime": e.get("runtime"),
                 "summary": short_summary(e.get("summary"), 160),
                 "image": ((e.get("image") or {}).get("medium") or "")} for e in eps]

    def _anilist(self, info, anilist_id):
        variables = {"id": int(anilist_id)} if anilist_id else {"s": info["title"]}
        res = fetch_json(ANILIST, {"query": ANILIST_Q, "variables": variables}, timeout=12)
        m = (res.get("data") or {}).get("Media")
        if not m:
            return
        t = m.get("title") or {}
        info["anime"] = True
        info.update(title=info["title"] or t.get("english") or t.get("romaji"),
                    year=info["year"] or str(m.get("seasonYear") or ""),
                    summary=info["summary"] or strip_html((m.get("description") or "").replace("<br>", "\n")),
                    poster=info["poster"] or (m.get("coverImage") or {}).get("extraLarge") or "",
                    backdrop=info["backdrop"] or m.get("bannerImage") or "",
                    rating=info["rating"] or (round(m["averageScore"] / 10, 1) if m.get("averageScore") else None),
                    runtime=info["runtime"] or m.get("duration"),
                    status=info["status"] or (m.get("status") or "").replace("_", " ").title())
        info["genres"] = list(dict.fromkeys((info["genres"] or []) + (m.get("genres") or [])))
        info["ids"]["anilist"] = m["id"]
        studios = [s["name"] for s in (m.get("studios") or {}).get("nodes") or []]
        if studios:
            info["studio"] = ", ".join(studios[:2])
        nxt = m.get("nextAiringEpisode")
        if nxt:
            info["nextEpisode"] = {"episode": nxt["episode"], "airingAt": nxt["airingAt"]}
        if not info["cast"]:
            info["cast"] = [{"name": e["node"]["name"]["full"], "role": (e.get("role") or "").title(),
                             "photo": (e["node"].get("image") or {}).get("medium") or ""}
                            for e in ((m.get("characters") or {}).get("edges") or [])[:10]]
        for link in m.get("externalLinks") or []:
            if link.get("type") == "STREAMING":
                name = NETWORK_TO_PROVIDER.get((link.get("site") or "").lower(), link.get("site"))
                self._add(info, name, url=link.get("url") or "", source="anilist")
        # episode pages on the service (Crunchyroll lists them) open the exact episode
        eps = []
        for e in (m.get("streamingEpisodes") or [])[:60]:
            prov = NETWORK_TO_PROVIDER.get((e.get("site") or "").lower(), e.get("site"))
            eps.append({"name": e.get("title") or "", "image": e.get("thumbnail") or "", "provider": prov,
                        "contentId": content_id(prov, e.get("url"))})
        if eps and not info["episodes"]:
            info["episodes"] = eps
        sim = []
        for n in (m.get("recommendations") or {}).get("nodes") or []:
            r = n.get("mediaRecommendation")
            if r:
                rt = r.get("title") or {}
                sim.append({"title": rt.get("english") or rt.get("romaji"), "year": str(r.get("seasonYear") or ""),
                            "kind": "tv", "poster": (r.get("coverImage") or {}).get("extraLarge") or "",
                            "anilist": r["id"], "anime": True})
        info["similar"] = info["similar"] or sim

    def _itunes(self, info, itunes_id):
        if itunes_id:
            res = fetch_json(f"https://itunes.apple.com/lookup?id={int(itunes_id)}", timeout=12)
        else:
            res = fetch_json("https://itunes.apple.com/search?media=movie&entity=movie&limit=6&term=" + quote(info["title"]), timeout=12)
        hits = res.get("results", [])
        y = info["year"]
        m = next((h for h in hits if y and (h.get("releaseDate") or "").startswith(y)), hits[0] if hits else None)
        if not m:
            return
        info.update(title=m.get("trackName") or info["title"], year=(m.get("releaseDate") or "")[:4] or y,
                    summary=strip_html(m.get("longDescription") or m.get("shortDescription")),
                    poster=(m.get("artworkUrl100") or "").replace("100x100bb", "800x1200bb"),
                    genres=[m.get("primaryGenreName", "")], runtime=round((m.get("trackTimeMillis") or 0) / 60000) or None)
        info["rated"] = m.get("contentAdvisoryRating") or ""
        info["director"] = m.get("artistName") or ""

    def _wikidata(self, info):
        """Wikidata holds each service's own ID for many titles; those IDs make the deep links."""
        api = "https://www.wikidata.org/w/api.php?format=json&"
        qid = None
        imdb = info["ids"].get("imdb")
        if imdb:
            r = fetch_json(api + "action=query&list=search&srlimit=1&srsearch=" + quote(f"haswbstatement:P345={imdb}"), timeout=12)
            hits = (r.get("query") or {}).get("search") or []
            qid = hits[0]["title"] if hits else None
        cands = [qid] if qid else []
        if not cands:
            r = fetch_json(api + "action=wbsearchentities&language=en&type=item&limit=6&search=" + quote(info["title"]), timeout=12)
            cands = [h["id"] for h in r.get("search", [])]
        if not cands:
            return
        r = fetch_json(api + "action=wbgetentities&props=claims&ids=" + "|".join(cands[:6]), timeout=15)
        ents = r.get("entities") or {}
        work = {"Q11424", "Q5398426", "Q24856", "Q506240", "Q1259759", "Q63952888", "Q20650540", "Q581714", "Q1107"}

        def vals(e, pid):
            return [c["mainsnak"].get("datavalue", {}).get("value") for c in e.get("claims", {}).get(pid, [])]

        best = None
        for q in cands:
            e = ents.get(q) or {}
            kinds = {v.get("id") for v in vals(e, "P31") if isinstance(v, dict)}
            if qid and q == qid:
                best = e
                break
            if not kinds & work:
                continue
            dates = [v.get("time", "")[1:5] for v in vals(e, "P577") + vals(e, "P580") if isinstance(v, dict)]
            if info["year"] and dates and info["year"] not in dates:
                continue
            best = e
            break
        if not best:
            return
        for p in PROVIDERS:
            for pid in p["wd"].get(info["kind"], []):
                for v in vals(best, pid):
                    if isinstance(v, str):
                        self._add(info, p["name"], value=v, source="wikidata")
        for pid, key in (("P345", "imdb"), ("P4947", "tmdbMovie"), ("P4983", "tmdbTv")):
            v = vals(best, pid)
            if v and isinstance(v[0], str):
                info["ids"].setdefault(key, v[0])

    def _tmdb(self, info):
        key = (self.app.cfg.get("tmdb_key") or "").strip()
        if not key:
            return
        kind = "movie" if info["kind"] == "movie" else "tv"
        tid = info["ids"].get("tmdbMovie" if kind == "movie" else "tmdbTv")
        base = "https://api.themoviedb.org/3/"
        auth = ("api_key=" + quote(key)) if len(key) < 40 else ""
        hdr = {"Authorization": "Bearer " + key} if len(key) >= 40 else {}

        def get(path):
            sep = "&" if "?" in path else "?"
            return json.loads(fetch(base + path + (sep + auth if auth else ""), headers=hdr, timeout=12))

        if not tid:
            r = get(f"search/{kind}?query={quote(info['title'])}" + (f"&year={info['year']}" if info["year"] and kind == "movie" else ""))
            res = r.get("results") or []
            if not res:
                return
            tid = res[0]["id"]
        d = get(f"{kind}/{tid}?append_to_response=credits,watch/providers,similar")
        img = "https://image.tmdb.org/t/p/"
        if d.get("backdrop_path") and not info["backdrop"]:
            info["backdrop"] = img + "w1280" + d["backdrop_path"]
        if d.get("poster_path") and not info["poster"]:
            info["poster"] = img + "w500" + d["poster_path"]
        info["summary"] = info["summary"] or d.get("overview") or ""
        info["rating"] = info["rating"] or (round(d["vote_average"], 1) if d.get("vote_average") else None)
        info["runtime"] = info["runtime"] or d.get("runtime") or ((d.get("episode_run_time") or [None])[0])
        info["tagline"] = d.get("tagline") or ""
        if not info["cast"]:
            info["cast"] = [{"name": c["name"], "role": c.get("character", ""),
                             "photo": (img + "w185" + c["profile_path"]) if c.get("profile_path") else ""}
                            for c in ((d.get("credits") or {}).get("cast") or [])[:12]]
        us = ((d.get("watch/providers") or {}).get("results") or {}).get("US") or {}
        for group in ("flatrate", "free", "ads"):
            for p in us.get(group) or []:
                name = NETWORK_TO_PROVIDER.get(p.get("provider_name", "").lower()) or p.get("provider_name")
                for known in PROVIDERS:
                    if known["name"].lower() in name.lower() or name.lower().startswith(known["name"].lower().split()[0]):
                        name = known["name"]
                self._add(info, name, source="tmdb")
        if not info["similar"]:
            for s in ((d.get("similar") or {}).get("results") or [])[:8]:
                info["similar"].append({"title": s.get("title") or s.get("name"), "kind": info["kind"],
                                        "year": (s.get("release_date") or s.get("first_air_date") or "")[:4],
                                        "poster": (img + "w342" + s["poster_path"]) if s.get("poster_path") else ""})

    def _with_apps(self, info):
        """Marks which services are on your TV, best first: on your TV with a direct link,
        on your TV, then the ones you'd need to install."""
        have = self.installed()
        out = []
        for p in info.get("providers", {}).values():
            a = have.get(p["name"])
            out.append({**p, "installed": bool(a), "app": a["id"] if a else "", "appName": a["name"] if a else p["name"]})
        out.sort(key=lambda p: (not p["installed"], not p["contentId"], p["name"]))
        saved = any(base_key(title_key(s.get("kind"), s.get("title"))) == base_key(info["key"]) for s in self.app.cfg["shows"])
        res = {k: v for k, v in info.items() if not k.startswith("_") and k != "providers"}
        res.update(providers=out, saved=saved, watchOn=next((p for p in out if p["installed"]), None))
        return res

    # -- opening a title in its app
    def play(self, item, provider=None, episode=None):
        """Opens the show or movie itself (not just the app). Uses the service's own ID when
        Hub knows it, otherwise asks Roku search to find it in that app."""
        kind = "movie" if item.get("kind") == "movie" else "tv"
        title = item.get("title") or ""
        try:
            info = self.details(item)
        except Exception:  # noqa: BLE001
            info = {"providers": [], "title": title, "kind": kind, "key": title_key(kind, title, item.get("year"))}
        provs = info.get("providers") or []
        pick = None
        if provider:
            pick = next((p for p in provs if p["name"] == provider or p.get("app") == provider), None)
        pick = pick or next((p for p in provs if p["installed"] and p["contentId"]), None) \
            or next((p for p in provs if p["installed"]), None)
        app_id = (pick or {}).get("app") or item.get("app") or ""
        app_name = (pick or {}).get("appName") or item.get("appName") or ""
        how = "search"
        if episode and episode.get("contentId") and pick and episode.get("provider") == pick["name"]:
            self.app.roku.launch(app_id, {"contentId": episode["contentId"], "mediaType": "episode"})
            how = "episode"
        elif pick and pick.get("contentId") and app_id:
            self.app.roku.launch(app_id, {"contentId": pick["contentId"], "mediaType": "movie" if kind == "movie" else "series"})
            how = "direct"
        else:
            params = {"keyword": title, "title": title, "type": "movie" if kind == "movie" else "tv-show"}
            if app_id:
                params["provider-id"] = app_id
                params["launch"] = "true"
            self.app.roku._req("/search/browse?" + urlencode(params), "POST")
        self.app.history.add(info if info.get("title") else item, app_id, app_name, (pick or {}).get("name", ""))
        return {"ok": True, "app": app_name, "how": how}


# ---------------------------------------------------------------- watch history

class History:
    """What you opened from Hub, newest first, for Keep watching. Kept per profile."""

    def __init__(self, app):
        self.app = app

    def add(self, info, app_id, app_name, provider):
        kind = "movie" if info.get("kind") == "movie" else "tv"
        key = base_key(info.get("key") or title_key(kind, info.get("title"), info.get("year")))
        entry = {"key": key, "title": info.get("title", ""), "kind": kind, "year": str(info.get("year") or ""),
                 "poster": info.get("poster", ""), "backdrop": info.get("backdrop", ""), "app": app_id,
                 "appName": app_name, "provider": provider, "anime": bool(info.get("anime")),
                 "tvmaze": (info.get("ids") or {}).get("tvmaze") or info.get("tvmaze"),
                 "anilist": (info.get("ids") or {}).get("anilist") or info.get("anilist"), "at": int(time.time())}
        items = [h for h in self.app.cfg.get("history") or [] if h.get("key") != key]
        self.app.cfg.update(history=([entry] + items)[:60])
        self.app.events.publish(type="widgets")

    def remove(self, key):
        self.app.cfg.update(history=[h for h in self.app.cfg.get("history") or [] if h.get("key") != key])
        self.app.events.publish(type="widgets")

    def clear(self):
        self.app.cfg.update(history=[])
        self.app.events.publish(type="widgets")


# ---------------------------------------------------------------- profiles

PROFILE_COLORS = ["#D9894A", "#8FB38A", "#7E97C9", "#C58A9B", "#C9A44C", "#5FA3A0"]
# Kept separately for each person. Everything else (notes, calendar, apps) is shared.
PROFILE_FIELDS = ["shows", "history", "passed", "quotes"]
PROFILE_HUB_FIELDS = ["wallpaper", "accent", "theme", "name"]


class Profiles:
    def __init__(self, app):
        self.app = app
        cfg = app.cfg
        if not cfg.get("profiles"):
            me = {"id": new_id(), "name": cfg["hub"].get("name") or "Me", "color": PROFILE_COLORS[0], "data": {}}
            cfg.update(profiles=[me], profile=me["id"])
        elif cfg.get("profile") not in {p["id"] for p in cfg["profiles"]}:
            cfg.update(profile=cfg["profiles"][0]["id"])

    def current(self):
        return next(p for p in self.app.cfg["profiles"] if p["id"] == self.app.cfg["profile"])

    def public(self):
        return [{"id": p["id"], "name": p["name"], "color": p["color"], "current": p["id"] == self.app.cfg["profile"]}
                for p in self.app.cfg["profiles"]]

    def _snapshot(self):
        cfg = self.app.cfg
        data = {k: cfg.get(k) for k in PROFILE_FIELDS}
        data["hub"] = {k: cfg["hub"].get(k) for k in PROFILE_HUB_FIELDS}
        return data

    def switch(self, pid):
        cfg = self.app.cfg
        profiles = cfg["profiles"]
        target = next((p for p in profiles if p["id"] == pid), None)
        if not target:
            raise ValueError("No such profile")
        if pid == cfg["profile"]:
            return self.public()
        for p in profiles:
            if p["id"] == cfg["profile"]:
                p["data"] = self._snapshot()
        data = target.get("data") or {}
        changes = {k: data.get(k) if data.get(k) is not None else [] for k in PROFILE_FIELDS}
        cfg.update(profiles=profiles, profile=pid, **changes)
        hub = {k: v for k, v in (data.get("hub") or {}).items() if v is not None}
        hub.setdefault("name", target["name"])
        cfg.update_hub(hub)
        self.app.recommender.top = None
        self.app.recommender.feed = []
        self.app.recommender.refresh_soon()
        self.app.events.publish(type="config")
        return self.public()

    def add(self, name, color=None):
        name = str(name or "").strip()[:24]
        if not name:
            raise ValueError("Give the profile a name")
        profiles = self.app.cfg["profiles"]
        if len(profiles) >= 6:
            raise ValueError("Hub keeps up to 6 profiles")
        color = color if color in PROFILE_COLORS else PROFILE_COLORS[len(profiles) % len(PROFILE_COLORS)]
        p = {"id": new_id(), "name": name, "color": color,
             "data": {"shows": [], "history": [], "passed": [], "quotes": [], "hub": {"name": name}}}
        self.app.cfg.update(profiles=profiles + [p])
        return self.public()

    def rename(self, pid, name, color=None):
        profiles = self.app.cfg["profiles"]
        for p in profiles:
            if p["id"] == pid:
                if name:
                    p["name"] = str(name).strip()[:24]
                if color in PROFILE_COLORS:
                    p["color"] = color
        self.app.cfg.update(profiles=profiles)
        return self.public()

    def delete(self, pid):
        cfg = self.app.cfg
        if pid == cfg["profile"]:
            raise ValueError("Switch to another profile first")
        cfg.update(profiles=[p for p in cfg["profiles"] if p["id"] != pid])
        return self.public()


def media_player(roku):
    """What the TV is playing in another app, from Roku's own player status."""
    body, _ = roku._req("/query/media-player", timeout=3)
    root = ET.fromstring(body)
    plugin = root.find("plugin")

    def ms(tag):
        n = root.find(tag)
        m = re.match(r"(\d+)", (n.text or "") if n is not None else "")
        return int(m.group(1)) if m else None

    return {"state": root.get("state") or "", "app": plugin.get("id") if plugin is not None else "",
            "appName": plugin.get("name") if plugin is not None else "", "position": ms("position"), "duration": ms("duration")}


# ---------------------------------------------------------------- Ask the TV

class Assistant:
    """Answers questions typed or spoken on the TV or the phone. Weather, time, the calendar,
    opening apps, the sleep timer and picking something to watch are built in; anything else
    goes to Claude (API key) or Ollama (on your PC) if one is set up in Hub settings."""

    WORD_NUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "ten": 10, "fifteen": 15, "twenty": 20,
                "thirty": 30, "forty": 40, "forty-five": 45, "sixty": 60, "ninety": 90, "half": 30, "an": 60, "a": 60}

    def __init__(self, app):
        self.app = app

    def ask(self, q):
        q = (q or "").strip()
        if not q:
            return {"answer": "Ask me about the weather, your schedule, or what to watch."}
        low = q.lower().strip(" ?!.")
        for fn in (self._sleep, self._weather, self._time, self._agenda, self._pick, self._open, self._watch):
            r = fn(low, q)
            if r:
                return r
        return self._ai(q)

    # -- built-in answers
    def _weather(self, low, q):
        if not re.search(r"\b(weather|rain|raining|snow|cold|hot|warm|jacket|coat|umbrella|temperature|outside|degrees|forecast|wear)\b", low):
            return None
        w = self.app.widgets.data.get("weather")
        if not w:
            return {"answer": "Set your weather location on the Hub page of the remote app and I'll know."}
        if "tomorrow" in low and len(w["days"]) > 1:
            d = w["days"][1]
            rain = f", {d['rain']}% chance of rain" if d["rain"] >= 20 else ""
            return {"answer": f"Tomorrow in {w['place']}: high {d['hi']}°, low {d['lo']}°{rain}."}
        tip = (". " + w["tip"] + ".") if w.get("tip") else "."
        today = w["days"][0] if w["days"] else None
        hl = f" Today: {today['hi']}° / {today['lo']}°." if today else ""
        return {"answer": f"It's {w['temp']}° and {w['text'].lower()} in {w['place']}{tip}{hl}".replace("..", ".")}

    def _time(self, low, q):
        if re.search(r"\bwhat time\b|\btime is it\b", low):
            return {"answer": "It's " + datetime.now().strftime("%-I:%M %p") + "."}
        if re.search(r"\bwhat('s| is) (the )?date\b|\bwhat day\b", low):
            return {"answer": "It's " + datetime.now().strftime("%A, %B %-d") + "."}
        return None

    def _agenda(self, low, q):
        if not re.search(r"\b(calendar|schedule|agenda|what('s| is) next|up next|plans|busy|events?|appointments?)\b", low):
            return None
        items = self.app.widgets.snapshot().get("agenda") or []
        if not items:
            return {"answer": "Nothing on your calendar coming up."}
        parts = []
        for e in items[:3]:
            when = datetime.fromtimestamp(e["start"]).strftime("%a" if e.get("allDay") else "%a %-I:%M %p")
            parts.append(f"{e['title']} ({when})")
        return {"answer": "Coming up: " + "; ".join(parts) + "."}

    def _sleep(self, low, q):
        if not re.search(r"\b(sleep|turn (the )?(tv )?off|shut (it )?off|off in)\b", low):
            return None
        if re.search(r"\b(cancel|stop|never ?mind)\b", low):
            self.app.sleep.set(0)
            return {"answer": "Sleep timer off.", "action": "sleep"}
        m = re.search(r"(\d+|an?|half|one|two|three|ten|fifteen|twenty|thirty|forty-five|forty|sixty|ninety)\s*(minutes?|mins?|hours?|hrs?)", low)
        if not m:
            return {"answer": "How long? Try “turn off in 30 minutes”."}
        n = m.group(1)
        if m.group(2).startswith("h"):
            num = 30 if n == "half" else 60 if n in ("a", "an") else (int(n) if n.isdigit() else self.WORD_NUM.get(n, 1)) * 60
        else:
            num = int(n) if n.isdigit() else self.WORD_NUM.get(n, 30)
        self.app.sleep.set(num)
        self.app.events.publish(type="widgets")
        return {"answer": f"Okay, the TV turns off in {num} minutes.", "action": "sleep"}

    def _pick(self, low, q):
        if not re.search(r"\b(what should (i|we) watch|(recommend|suggest)\b.*\b(show|movie|film|series|anime|watch|something)|"
                         r"pick (something|a show|a movie)|something to watch|"
                         r"(a|some) (good )?(show|movie)s? (to|for)|i'?m bored|surprise me|choose for me)\b", low):
            return None
        kind = "movie" if re.search(r"\bmovies?\b|\bfilm\b", low) else "tv" if re.search(r"\b(shows?|series)\b", low) else "any"
        genre = next((g for w, g in GENRE_WORDS.items() if w in low), None)
        recs = self.app.recommender.pick(kind, genre, n=1)
        if not recs:
            return {"answer": "I couldn't find a pick right now. Try again in a minute."}
        r = recs[0]
        what = "movie" if r["kind"] == "movie" else "show"
        year = f" ({r['year']})" if r.get("year") else ""
        where = f" It's on {r['appName']}." if r.get("appName") else ""
        return {"answer": f"Try {r['title']}{year}, a {what}. {r['reason']}.{where}", "rec": r}

    def _open(self, low, q):
        m = re.match(r"^(open|launch|start|go to)\s+(.+)$", low)
        if not m:
            return None
        name = m.group(2).strip()
        try:
            apps = self.app.roku.apps()
        except Exception:  # noqa: BLE001
            return {"answer": "I can't reach the TV right now."}
        hit = next((a for a in apps if a["name"].lower() == name), None) or next((a for a in apps if name in a["name"].lower()), None)
        if not hit:
            return None
        self.app.roku.launch(hit["id"])
        return {"answer": f"Opening {hit['name']}.", "action": "launched"}

    def _watch(self, low, q):
        m = re.match(r"^(play|watch|put on)\s+(.+?)(\s+on\s+(.+))?$", low)
        if not m:
            return None
        title = m.group(2).strip()
        mine = next((x for x in self.app.cfg["shows"] if x["title"].lower() == title), None)
        show = mine or {"title": title, "kind": "tv", "app": "", "appName": ""}
        if m.group(4) and not show.get("app"):
            try:
                a = next((a for a in self.app.roku.apps() if m.group(4).strip() in a["name"].lower()), None)
                if a:
                    show = {**show, "app": a["id"], "appName": a["name"]}
            except Exception:  # noqa: BLE001
                pass
        try:
            self.app.titles.play(show)
        except Exception:  # noqa: BLE001
            return {"answer": "I can't reach the TV right now."}
        where = f" on {show['appName']}" if show.get("appName") else ""
        name = show["title"] if mine else q[len(m.group(1)) + 1:].strip()
        if m.group(4) and not mine:
            name = re.sub(r"\s+on\s+.+$", "", name, flags=re.I)
        return {"answer": f"Looking for {name}{where}.", "action": "launched"}

    # -- anything else: Claude or Ollama
    def _context(self):
        if not (self.app.cfg["ai"] or {}).get("share"):
            return "Now: " + datetime.now().strftime("%A %B %-d, %-I:%M %p") + "."
        w = self.app.widgets.data.get("weather")
        lines = [f"Now: {datetime.now().strftime('%A %B %-d, %-I:%M %p')}."]
        if w:
            lines.append(f"Weather in {w['place']}: {w['temp']}°{w['unit']}, {w['text']}. {w.get('tip', '')}")
        ag = self.app.widgets.snapshot().get("agenda") or []
        if ag:
            lines.append("Calendar: " + "; ".join(f"{e['title']} at {datetime.fromtimestamp(e['start']).strftime('%a %-I:%M %p')}" for e in ag[:4]))
        if self.app.cfg["shows"]:
            lines.append("Their saved shows: " + ", ".join(x["title"] for x in self.app.cfg["shows"][:30]))
        return "\n".join(lines)

    def _ai(self, q):
        system = ("You are Hub, the assistant on a family's TV. Answer in one to three short, friendly sentences "
                  "that read well on a TV screen. No markdown, no lists.\n" + self._context())
        res = self._call(system, q, 300)
        if res is not None:
            return res
        return {"answer": "I can do the weather, your schedule, opening apps, sleep timers and picking something to "
                          "watch. For other questions, set up Ollama or a Claude API key on the Hub page of the remote app."}

    def raw(self, q, max_tokens=500):
        """Plain text from the AI, or "" when none is set up or it fails."""
        res = self._call("Answer exactly in the format asked. No extra text.", q, max_tokens)
        return (res or {}).get("answer", "") if res and not res.get("error") else ""

    def _call(self, system, q, max_tokens):
        ai = self.app.cfg["ai"] or {}
        try:
            if ai.get("provider") == "claude" and ai.get("key"):
                body = {"model": ai.get("model") or "claude-haiku-5-5", "max_tokens": max_tokens, "system": system,
                        "messages": [{"role": "user", "content": q}]}
                req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=json.dumps(body).encode(),
                                             headers={"x-api-key": ai["key"], "anthropic-version": "2023-06-01",
                                                      "content-type": "application/json"})
                with urllib.request.urlopen(req, timeout=40) as r:
                    res = json.loads(r.read())
                text = "".join(c.get("text", "") for c in res.get("content", []) if c.get("type") == "text").strip()
                return {"answer": text or "I don't have an answer for that."}
            if ai.get("provider") == "ollama" and ai.get("url"):
                url = ai["url"].rstrip("/")
                if "://" not in url:
                    url = "http://" + url
                if not re.search(r":\d+$", url):
                    url += ":11434"
                body = {"model": ai.get("model") or "llama3.2", "stream": False,
                        "messages": [{"role": "system", "content": system}, {"role": "user", "content": q}]}
                req = urllib.request.Request(url + "/api/chat", data=json.dumps(body).encode(), headers={"content-type": "application/json"})
                with LAN.open(req, timeout=90) as r:
                    res = json.loads(r.read())
                return {"answer": (res.get("message") or {}).get("content", "").strip() or "I don't have an answer for that."}
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:200]
            log("Ask:", e.code, detail)
            return {"error": True, "answer": "The AI service said no" + (" (check the API key)." if e.code in (401, 403) else f" ({e.code}).")}
        except Exception as e:  # noqa: BLE001
            log("Ask:", e)
            return {"error": True, "answer": "I couldn't reach the AI service. Check the address in Hub settings."}
        return None


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


def watch_tv(app, roku, cfg, events, sleep, stop):
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
            if power == "PowerOn":
                act = roku.active()
                if act["id"] and act["id"] != "dev":
                    recent = [a for a in cfg.get("recent_apps") or [] if a != act["id"]]
                    if (cfg.get("recent_apps") or [None])[0] != act["id"]:
                        cfg.update(recent_apps=([act["id"]] + recent)[:20])
                    try:
                        np = media_player(roku)
                        np["appName"] = np["appName"] or act["name"]
                        app.nowplaying = {**np, "at": int(time.time())}
                    except Exception:  # noqa: BLE001
                        app.nowplaying = {"state": "open", "app": act["id"], "appName": act["name"], "at": int(time.time())}
                else:
                    app.nowplaying = None
            else:
                app.nowplaying = None
        except Exception:  # noqa: BLE001
            pass
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
    IDEM = {}  # Idempotency-Key -> (time, code, body, ctype), so a retried POST runs once
    IDEM_LOCK = threading.Lock()
    _idem = None

    def log_message(self, fmt, *args):
        pass

    # -- helpers
    def _send(self, code, body, ctype, extra=None):
        if self._idem and self.command == "POST":
            with Handler.IDEM_LOCK:
                Handler.IDEM[self._idem] = (time.time(), code, body, ctype)
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
            elif path.startswith("/fonts/"):
                name = safe_name(path[len("/fonts/"):])
                self._file(os.path.join(HERE, "fonts", name),
                           "font/woff" if name.endswith(".woff") else None)
            elif path == "/api/legal":
                self._json(200, {"ok": True, **LEGAL})
            elif path == "/api/title":
                self._json(200, {"ok": True, "title": S.titles.details(q, fresh=bool(q.get("fresh")))})
            elif path == "/api/title/episodes":
                self._json(200, {"ok": True, "episodes": S.titles.episodes(q.get("season", "0"))})
            elif path == "/api/history":
                self._json(200, {"ok": True, "history": S.cfg.get("history") or []})
            elif path == "/api/profiles":
                self._json(200, {"ok": True, "profiles": S.profiles.public()})
            elif path == "/api/nowplaying":
                self._json(200, {"ok": True, "nowPlaying": S.nowplaying})
            elif path == "/api/recommend":
                items = S.recommender.pick(q.get("kind", "any"), q.get("genre"), max(1, min(10, int(q.get("n", 6)))),
                                           bool(q.get("fresh")))
                self._json(200, {"ok": True, "items": items})
            elif path == "/api/update/check":
                self._json(200, {"ok": True, **S.check_update()})
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
            elif path == "/api/music":
                self._json(200, S.library.music())
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
            elif path == "/api/shows/search":
                self._json(200, {"ok": True, "results": S.shows.search(q.get("q", ""))})
            elif path == "/api/thumb":
                if q.get("tiny"):
                    self._file(S.tiny(q.get("u", "")), "image/jpeg")
                else:
                    f, ctype = S.thumb(q.get("u", ""))
                    self._file(f, ctype)
            elif path.startswith("/api/notes/img/"):
                self._file(os.path.join(S.notes_dir, safe_name(path.rsplit("/", 1)[1])), "image/png")
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
        key = (self.headers.get("Idempotency-Key") or "")[:80]
        if key:
            with Handler.IDEM_LOCK:
                now = time.time()
                for k in [k for k, v in Handler.IDEM.items() if now - v[0] > 900]:
                    del Handler.IDEM[k]
                hit = Handler.IDEM.get(key)
            if hit:
                length = int(self.headers.get("Content-Length") or 0)
                if length:
                    self.rfile.read(length)
                self._send(hit[1], hit[2], hit[3])
                return
            self._idem = key
        try:
            # uploads first (raw body, not JSON)
            if path == "/api/wallpapers/upload":
                src = self._upload(os.path.join(S.data_dir, "uploads"), 60)
                self._ok(**S.add_wallpaper(src))
                return
            if path == "/api/notes/draw":
                src = self._upload(os.path.join(S.data_dir, "uploads"), 20)
                self._ok(note=S.add_drawing(src))
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
                if params and b["app"] == "details":  # open one title's page on the TV
                    params.update({k: str(b[k])[:120] for k in ("title", "kind", "year", "tvmaze", "anilist") if b.get(k)})
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
                if "theme" in allowed or "modes" in allowed:
                    merged = Config._migrate({**S.cfg["hub"], **allowed})
                    allowed.update({k: merged[k] for k in ("theme", "modes") if k in allowed})
                if "accent" in allowed and str(allowed["accent"]).upper() not in ACCENTS:
                    raise ValueError("Pick one of Hub's accent colors")
                if "rows" in allowed:
                    allowed["rows"] = Config._migrate({**S.cfg["hub"], "rows": allowed["rows"]})["rows"]
                if "widgets" in allowed:
                    allowed["widgets"] = Config._migrate({**S.cfg["hub"], "widgets": allowed["widgets"]})["widgets"]
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
                note = {"id": new_id(), "kind": "text", "text": str(b.get("text", ""))[:500], "color": b.get("color") or "#FFD166",
                        "created": time.time()}
                S.cfg.update(notes=([note] + S.cfg["notes"])[:30])
                S.events.publish(type="widgets")
                S.events.publish(type="toast", text="New note: " + note["text"][:60])
                self._ok(note=note)
            elif path == "/api/notes/delete":
                gone = [n for n in S.cfg["notes"] if n["id"] == b.get("id")]
                for n in gone:
                    if n.get("kind") == "drawing":
                        try:
                            os.remove(os.path.join(S.notes_dir, safe_name(n["id"] + ".png")))
                        except OSError:
                            pass
                S.cfg.update(notes=[n for n in S.cfg["notes"] if n["id"] != b.get("id")])
                S.events.publish(type="widgets")
                self._ok()
            elif path == "/api/shows/add":
                self._ok(show=S.shows.add(b), shows=S.cfg["shows"])
                S.recommender.refresh_soon()
            elif path == "/api/shows/remove-title":
                k = base_key(title_key(b.get("kind"), b.get("title")))
                S.cfg.update(shows=[x for x in S.cfg["shows"] if base_key(title_key(x.get("kind"), x.get("title"))) != k])
                S.events.publish(type="widgets")
                self._ok(shows=S.cfg["shows"])
            elif path == "/api/shows/update":
                S.shows.update(b)
                self._ok(shows=S.cfg["shows"])
            elif path == "/api/shows/delete":
                S.shows.delete(b.get("id"))
                self._ok(shows=S.cfg["shows"])
            elif path == "/api/shows/play":
                show = S.shows.find(b.get("id")) if b.get("id") else {"title": b.get("title", ""), "kind": b.get("kind", "tv"),
                                                                        "app": b.get("app", ""), "appName": b.get("appName", "")}
                if not show or not show.get("title"):
                    raise ValueError("Unknown show")
                self._tv(lambda: S.titles.play(show, b.get("provider")))
            elif path == "/api/title/play":
                item = b.get("item") or b
                if not item.get("title"):
                    raise ValueError("Nothing to watch")
                self._tv(lambda: S.titles.play(item, b.get("provider"), b.get("episode")))
            elif path == "/api/history/remove":
                S.history.remove(str(b.get("key", "")))
                self._ok()
            elif path == "/api/history/clear":
                S.history.clear()
                self._ok()
            elif path == "/api/profiles/switch":
                self._ok(profiles=S.profiles.switch(str(b.get("id", ""))))
            elif path == "/api/profiles/add":
                self._ok(profiles=S.profiles.add(b.get("name"), b.get("color")))
            elif path == "/api/profiles/rename":
                self._ok(profiles=S.profiles.rename(str(b.get("id", "")), b.get("name"), b.get("color")))
            elif path == "/api/profiles/delete":
                self._ok(profiles=S.profiles.delete(str(b.get("id", ""))))
            elif path == "/api/recommend/watch":
                item = {"title": str(b.get("title", ""))[:120], "kind": b.get("kind", "tv"), "app": b.get("app", ""),
                        "appName": b.get("appName", "")}
                if not item["title"]:
                    raise ValueError("Nothing to watch")
                self._tv(lambda: S.titles.play({**item, **{k: b[k] for k in ("year", "tvmaze", "anilist", "anime", "key") if k in b}}, b.get("provider")))
            elif path == "/api/recommend/pass":
                S.recommender.passed(str(b.get("key", ""))[:200], undo=bool(b.get("undo")))
                self._ok()
            elif path == "/api/recommend/reset":
                S.cfg.update(passed=[])
                self._ok()
            elif path == "/api/recommend/seen":
                S.recommender.note_seen(str(b.get("key", ""))[:200])
                self._ok()
            elif path == "/api/update/apply":
                self._ok(**S.apply_update())
                threading.Timer(1.0, S.restart).start()
            elif path == "/api/tv/remove-channel":
                S.remove_tv_channel(str(b.get("password", "")))
                self._ok()
            elif path == "/api/uninstall":
                if b.get("confirm") != "REMOVE":
                    raise ValueError("Type REMOVE to confirm")
                tv = "skipped"
                if b.get("password"):
                    try:
                        S.remove_tv_channel(str(b["password"]))
                        tv = "removed"
                    except Exception as e:  # noqa: BLE001
                        tv = "failed: " + str(e)
                self._ok(tv=tv)
                threading.Timer(1.0, S.uninstall).start()
            elif path == "/api/quotes/add":
                text = str(b.get("text", "")).strip()[:200]
                if not text:
                    raise ValueError("Type a quote first")
                qt = {"id": new_id(), "text": text, "by": str(b.get("by", "")).strip()[:60]}
                S.cfg.update(quotes=S.cfg["quotes"] + [qt])
                S.events.publish(type="widgets")
                self._ok(quotes=S.cfg["quotes"])
            elif path == "/api/quotes/delete":
                S.cfg.update(quotes=[x for x in S.cfg["quotes"] if x["id"] != b.get("id")])
                S.events.publish(type="widgets")
                self._ok(quotes=S.cfg["quotes"])
            elif path == "/api/ask":
                res = S.assistant.ask(str(b.get("q", ""))[:500])
                if b.get("source") != "tv":
                    S.events.publish(type="ask", q=str(b.get("q", ""))[:500], answer=res.get("answer", ""), rec=res.get("rec"))
                self._ok(**res)
            elif path == "/api/ai":
                ai = dict(S.cfg["ai"] or {})
                for k in ("provider", "model", "url"):
                    if k in b:
                        ai[k] = str(b[k] or "").strip()
                if "share" in b:
                    ai["share"] = bool(b["share"])
                    S.recommender.ai_pool = {}
                if b.get("key"):
                    ai["key"] = str(b["key"]).strip()
                if b.get("clearKey"):
                    ai["key"] = ""
                S.cfg.update(ai=ai)
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
                if "tmdb_key" in b:
                    allowed["tmdb_key"] = re.sub(r"[^A-Za-z0-9._\-]", "", str(b["tmdb_key"]))[:400]
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
        self.shows = Shows(cfg, self.roku, self.events)
        self.assistant = Assistant(self)
        self.recommender = Recommender(self)
        self.titles = Titles(self)
        self.history = History(self)
        self.profiles = Profiles(self)
        self.nowplaying = None
        self.notes_dir = os.path.join(data_dir, "notes")
        os.makedirs(self.notes_dir, exist_ok=True)
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
            "events": c["events"], "notes": c["notes"], "quotes": c["quotes"], "shows": c["shows"],
            "passed": len(c.get("passed") or []), "accents": ACCENTS,
            "history": (c.get("history") or [])[:30], "profiles": self.profiles.public(),
            "nowPlaying": self.nowplaying, "hasTmdb": bool(c.get("tmdb_key")),
            "ai": {"provider": (c["ai"] or {}).get("provider", ""), "model": (c["ai"] or {}).get("model", ""),
                   "url": (c["ai"] or {}).get("url", ""), "hasKey": bool((c["ai"] or {}).get("key")),
                   "share": bool((c["ai"] or {}).get("share"))},
        }

    def hub_state(self):
        return {"ok": True, "hub": self.cfg["hub"], "wallpaper": "/api/wallpaper.jpg?v=" + self.wallpaper_key(),
                "widgets": self.widgets.snapshot(), "sleep": self.sleep.remaining(),
                "quotes": self.cfg["quotes"], "shows": self.cfg["shows"], "pick": self.recommender.top,
                "foryou": self.recommender.feed, "history": (self.cfg.get("history") or [])[:16],
                "profiles": self.profiles.public(), "recentApps": (self.cfg.get("recent_apps") or [])[:12],
                "nowPlaying": self.nowplaying,
                "adblock": {"enabled": self.cfg["adblock"], "blocked": self.adblock.blocked},
                "server_time": int(time.time())}

    # -- updates, removing Hub
    def check_update(self):
        try:
            src = fetch(UPDATE_BASE + "hub_server.py", timeout=15).decode("utf-8", "ignore")
            m = re.search(r'^VERSION = "([\d.]+)"', src, re.M)
            latest = m.group(1) if m else ""
        except Exception as e:  # noqa: BLE001
            return {"current": VERSION, "latest": "", "error": f"Couldn't reach GitHub ({e})"}
        newer = tuple(int(x) for x in latest.split(".")) > tuple(int(x) for x in VERSION.split(".")) if latest else False
        return {"current": VERSION, "latest": latest, "newer": newer}

    def apply_update(self):
        got = {}
        for name in UPDATE_FILES:
            got[name] = fetch(UPDATE_BASE + name, timeout=30)
        if b"VERSION = " not in got["hub_server.py"]:
            raise ValueError("The download looks wrong; nothing changed")
        for name, data in got.items():
            dest = os.path.join(HERE, name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest + ".tmp", "wb") as f:
                f.write(data)
            os.replace(dest + ".tmp", dest)
        return {"updated": len(got)}

    def restart(self):
        log("Restarting with the updated files")
        os.execv(sys.executable, [sys.executable] + sys.argv)

    def remove_tv_channel(self, password):
        """Deletes the sideloaded Hub channel through the TV's developer installer (needs the dev password)."""
        ip = self.cfg["tv_ip"]
        if not ip or not password:
            raise ValueError("Needs the TV address and its developer password")
        url = f"http://{ip}/plugin_install"
        pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()  # Roku's realm is "rokudev"; accept any
        pw.add_password(None, url, "rokudev", password)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))
        bound = "----hub" + new_id()
        body = (f"--{bound}\r\nContent-Disposition: form-data; name=\"mysubmit\"\r\n\r\nDelete\r\n"
                f"--{bound}\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n\r\n--{bound}--\r\n").encode()
        req = urllib.request.Request(url, data=body, headers={"Content-Type": f"multipart/form-data; boundary={bound}"})
        try:
            with opener.open(req, timeout=20) as r:
                text = r.read().decode("utf-8", "ignore")
        except urllib.error.HTTPError as e:
            raise ValueError("The TV said no. Check the developer password." if e.code == 401 else f"The TV said {e.code}") from e
        log("TV channel removal:", re.sub(r"\s+", " ", strip_html(text))[:160])

    def uninstall(self):
        """Deletes everything Hub keeps: settings, notes, wallpapers, caches, uploads and the server files.
        Your /media folders are read-only to Hub and stay untouched."""
        log("Removing Hub and all of its data")
        unit = os.path.expanduser("~/.config/systemd/user/hub-server.service")
        if os.path.exists(unit):  # installed with install.sh rather than CasaOS
            subprocess.run(["systemctl", "--user", "disable", "hub-server.service"], check=False)
            try:
                os.remove(unit)
            except OSError:
                pass
        for root in {self.data_dir, HERE}:
            for name in os.listdir(root):
                path = os.path.join(root, name)
                try:
                    if os.path.isdir(path) and not os.path.islink(path):
                        shutil.rmtree(path)
                    else:
                        os.remove(path)
                except OSError as e:
                    log("Couldn't remove", path, e)
        os._exit(0)

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
            name = value if kind == "preset" and value in PRESETS else "dusk"
            out = os.path.join(self.cache, "presets", name + ".jpg")
            if not os.path.exists(out):
                self.proxy.call("render_preset", name, out)
            return out
        except Exception as e:  # noqa: BLE001
            log("Wallpaper:", e)
            out = os.path.join(self.cache, "presets", "dusk.jpg")
            if not os.path.exists(out):
                self.proxy.call("render_preset", "dusk", out)
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

    def thumb(self, url):
        """Caches a remote picture (posters, thumbnails) so the TV loads it fast over plain HTTP."""
        if not re.match(r"^https?://", url or ""):
            raise FileNotFoundError(url)
        out = os.path.join(self.cache, "thumbs", hashlib.sha1(url.encode()).hexdigest() + ".img")
        if not os.path.exists(out):
            data = fetch(url, timeout=20)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with open(out + ".tmp", "wb") as f:
                f.write(data)
            os.replace(out + ".tmp", out)
        with open(out, "rb") as f:
            head = f.read(12)
        ctype = "image/png" if head.startswith(b"\x89PNG") else "image/webp" if head[8:12] == b"WEBP" else "image/jpeg"
        return out, ctype

    def tiny(self, url):
        """An 8x12 preview of a poster, shown blurred while the full picture loads."""
        full, _ = self.thumb(url)
        out = os.path.join(self.cache, "tiny", os.path.basename(full)[:-4] + ".jpg")
        if not os.path.exists(out):
            self.proxy.call("render_image", self.local_url(full), 8, 12, out, "cover")
        return out

    def add_drawing(self, src):
        note_id = new_id()
        dest = os.path.join(self.notes_dir, note_id + ".png")
        os.replace(src, dest)
        note = {"id": note_id, "kind": "drawing", "image": f"/api/notes/img/{note_id}.png", "text": "",
                "color": "#1B1F3B", "created": time.time()}
        keep = ([note] + self.cfg["notes"])[:30]
        for old in self.cfg["notes"]:
            if old not in keep and old.get("kind") == "drawing":
                try:
                    os.remove(os.path.join(self.notes_dir, old["id"] + ".png"))
                except OSError:
                    pass
        self.cfg.update(notes=keep)
        self.events.publish(type="widgets")
        self.events.publish(type="toast", text="New note from your phone")
        return note

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
    threading.Thread(target=watch_tv, args=(app, app.roku, cfg, app.events, app.sleep, stop), daemon=True).start()
    threading.Thread(target=app.recommender.warm, daemon=True).start()

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
