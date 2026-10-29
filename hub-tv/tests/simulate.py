#!/usr/bin/env python3
"""Runs the Hub channel in brs-engine (a BrightScript simulator), drives it with remote keys
over its ECP port, and saves a screenshot after each step.
   simulate.py <app dir or zip> <out dir> [theme]"""
import os, pty, select, subprocess, sys, time, urllib.request

app, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
ECP = "http://127.0.0.1:8070"
shot = os.path.abspath(os.path.join(out, "_shot.png"))
log = open(os.path.join(out, "brs.log"), "wb")
pid, fd = pty.fork()
if pid == 0:
    os.environ.update(BRS_ECP_PORT="8070", BRS_NO_UPDATE_CHECK="1", COLUMNS="120", LINES="40")
    os.execvp("brs-cli", ["brs-cli", "--ecp", "-s", shot, "-k", "server=127.0.0.1:8765", "-c", "0", app])


def pump(secs):
    end = time.time() + secs
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.2)
        if r:
            try:
                data = os.read(fd, 65536)
            except OSError:
                return False
            log.write(data)
            log.flush()
    return True


def key(k, wait=1.2):
    try:
        urllib.request.urlopen(urllib.request.Request(f"{ECP}/keypress/{k}", data=b"", method="POST"), timeout=5).read()
    except Exception as e:
        print("key", k, "failed:", e)
    pump(wait)


def snap(name):
    if os.path.exists(shot):
        os.remove(shot)
    os.write(fd, b"\x13")  # Ctrl+S saves the screen
    pump(2)
    if os.path.exists(shot):
        os.replace(shot, os.path.join(out, name + ".png"))
        print("saved", name)
    else:
        print("no screenshot for", name)


pump(25)
snap("01-home")
key("Right"); snap("02-hero-button")
key("Left"); key("Left", 3); snap("03-hero-previous")
key("Down"); key("Down", 4); snap("04-row")
key("Right", 3); snap("05-row-right")
key("Info", 10); snap("06-details")
key("Down", 3); snap("07-details-down")
key("Back", 3); key("Up"); key("Up"); key("Up"); key("Up")
key("Right", 3); snap("08-tab-shows")
key("Right"); key("Right"); key("Right", 3); snap("09-tab-apps")
key("Left"); key("Left"); key("Left"); key("Left", 2)
for _ in range(6):
    key("Down", 0.6)
key("Up", 1); key("Up", 1)
snap("10-before-settings")
os.write(fd, b"\x04")
pump(3)
try:
    os.kill(pid, 9)
except OSError:
    pass
