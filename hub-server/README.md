# Hub

Hub is a home screen for a Roku TV. It has three parts:

- **Hub.zip**: the Roku channel (folder `hub-tv`). You sideload it with Roku's developer mode.
- **Hub server**: this folder. It runs on a computer on your Wi-Fi and serves the control page at `http://<server>:8765`.
- **Hub Remote**: `HubRemote.apk` in the repo root, for Android tablets and phones.

The TV gets everything through the server: channels, the browser, weather, recommendations, notes and files you send.

What Hub does:

- **Two looks:** Hub (a featured title, then rows of picks, apps and today's widgets) or Console (square tiles with the focused title's artwork filling the screen). Switch in TV Settings, Hub Remote or the control page.
- **Details for every show and movie:** summary, seasons and episodes, cast, ratings, more like it, and which of your channels stream it.
- **Opens the title itself:** Watch goes straight to the show or episode in Netflix, Crunchyroll, Hulu, Disney+, Max or Prime Video when Hub knows that service's ID for it, and searches your channels otherwise.
- **Keep watching, profiles, music, morning and night looks, Ask the TV, drawn notes, a browser with ads blocked, a media library and a sleep timer.**

## What you need

- A Roku TV or stick with developer mode on (press Home 3×, Up 2×, Right, Left, Right, Left, Right on the remote).
- A computer that stays on: CasaOS, or Debian/Ubuntu with Python 3.10 or newer.
- Optional: an Ollama install or a Claude API key, for questions Hub can't answer itself.

## Install the server

**CasaOS:** unzip `hub-server.zip` into `/DATA/AppData/hub-server/` with the Files app. Then open the App Store, choose **+ › Install a customized app › Import**, and paste `docker-compose.yml`.

**Debian or Ubuntu:** unzip `hub-server.zip` anywhere and run:

```bash
./install.sh            # install or update
./install.sh --check    # version, service status, newer version on GitHub
./install.sh --uninstall
```

## Install the channel

Open `http://<your TV's IP>` in a browser, sign in with the developer password, upload `Hub.zip` and press **Install**. On the TV, open **Hub › Settings › Set server address** and type the server's IP.

## Update

On the control page, **Hub › Server › Check for updates** looks on GitHub and updates the server in place. New channel builds come as `Hub.zip` on the [releases page](../../releases).

## Remove

- **Everything:** **Remove Hub** at the bottom of the control page's Hub tab. It deletes the server's files and data, and with the TV's developer password it deletes the channel too. On CasaOS, finish with **Uninstall** in the app's menu.
- **Only the channel:** **Hub › Settings › Remove Hub from this TV** on the TV.
- **Hub Remote:** **Hub tab › About Hub › Uninstall Hub Remote**.

Privacy and terms sit at the bottom of the control page's Hub tab and in the TV's Settings.
