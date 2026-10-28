' Hub's frame: loads everything from the Hub server, shows the home look you picked
' (Hub or Console), opens Hub's own screens on top, and handles settings and messages.

sub init()
    m.tools = [
        { id: "pick", name: "Hub recommends", icon: "pkg:/images/i_pick.png" },
        { id: "ask", name: "Ask Hub", icon: "pkg:/images/i_ask.png" },
        { id: "browser", name: "Browser", icon: "pkg:/images/globe.png" },
        { id: "library", name: "Library", icon: "pkg:/images/library.png" },
        { id: "music", name: "Music", icon: "pkg:/images/i_music.png" },
        { id: "cast", name: "Cast your PC", icon: "pkg:/images/cast.png" },
        { id: "settings", name: "Settings", icon: "pkg:/images/gear.png" }
    ]
    m.server = regRead("browserServer", "")
    m.hub = { accent: "#D9894A", clock24: false, name: "", theme: "hub", rows: [], widgets: [], favorites: [], screensaver: { minutes: 5 }, modes: { auto: true, morning: 5, night: 21 } }
    m.state = { widgets: {}, quotes: [], shows: [], history: [], foryou: [], profiles: [] }
    m.serverOk = false
    m.wallUrl = ""
    m.channels = []
    m.ytAppId = "837"
    m.home = invalid
    m.homeTheme = ""
    m.current = invalid
    m.dialog = invalid
    m.dialogKind = ""
    m.idleSecs = 0
    m.mode = "day"
    m.search = invalid
    m.searchSeq = 0
    m.askedProfile = false

    m.top.findNode("toastText").font = fontFile("medium", 18)
    m.appLayer = m.top.findNode("appLayer")
    m.homeLayer = m.top.findNode("homeLayer")
    m.toastTimer = m.top.findNode("toastTimer")
    m.toastTimer.observeField("fire", "onToastTimer")
    driftLoop(m.top.findNode("breathAnim"), m.top.findNode("breathInterp"), [1.0, 1.0], [1.05, 1.05], 48.0)
    m.top.findNode("wallB").observeField("loadStatus", "onWallLoaded")

    clock = m.top.findNode("clockTimer")
    clock.observeField("fire", "onClock")
    clock.control = "start"
    st = m.top.findNode("stateTimer")
    st.observeField("fire", "loadState")
    st.control = "start"
    idle = m.top.findNode("idleTimer")
    idle.observeField("fire", "onIdleTick")
    idle.control = "start"

    m.events = CreateObject("roSGNode", "EventTask")
    m.events.observeField("event", "onServerEvent")
    m.events.observeField("connected", "onEventsConnected")
    m.events.server = m.server
    m.events.control = "RUN"

    m.top.observeField("launchParams", "onLaunchParams")

    buildHome()
    onClock()
    loadState()
    loadChannels()
    m.top.findNode("breathAnim").control = "start"
    if m.server = "" then showToast("Set the Hub server in Settings (press the star button)")
end sub

' =====================================================================  data

sub loadState()
    if m.server = "" then return
    startRequest(serverUrl(m.server) + "/api/hub/state", "GET", "", "onState")
end sub

sub onState(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid
        if m.serverOk then showToast("Can't reach the Hub server")
        m.serverOk = false
        return
    end if
    m.serverOk = true
    m.hub = data.hub
    m.state = data
    setWallpaper(absUrl(m.server, data.wallpaper))
    computeMode()
    buildHome()
    if m.channels.count() = 0 then loadChannels()
    ' more than one person: ask who's watching once when Hub starts
    profiles = data.profiles
    if not m.askedProfile and profiles <> invalid and profiles.count() > 1 and m.current = invalid
        m.askedProfile = true
        openApp("ProfileApp", { profiles: profiles, accent: m.hub.accent })
    end if
    m.askedProfile = true
end sub

' Roku OS 12+ blocks a channel from using the TV's control port on its own TV, so the
' channel list, icons and launches all go through the Hub server.
sub loadChannels()
    if m.server = "" then return
    startRequest(serverUrl(m.server) + "/api/tv/apps", "GET", "", "onApps")
end sub

sub onApps(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    m.channels = []
    if data <> invalid and data.apps <> invalid
        for each a in data.apps
            m.channels.push({ id: a.id, name: a.name })
            if LCase(a.name) = "youtube" then m.ytAppId = a.id
        end for
    end if
    pushData()
end sub

' =====================================================================  the home look

sub buildHome()
    theme = "hub"
    if txt(m.hub.theme) = "console" then theme = "console"
    if m.home = invalid or theme <> m.homeTheme
        if m.home <> invalid
            m.home.unobserveField("action")
            m.homeLayer.removeChild(m.home)
        end if
        if theme = "console"
            m.home = CreateObject("roSGNode", "HomeConsole")
        else
            m.home = CreateObject("roSGNode", "HomeHub")
        end if
        m.homeTheme = theme
        m.home.observeField("action", "onHomeAction")
        m.homeLayer.appendChild(m.home)
        m.homeLayer.opacity = 0
        animTo(m.homeLayer, "opacity", 1.0, "gentle")
    end if
    pushData()
    onClock()
    if m.current = invalid and m.dialog = invalid then m.home.setFocus(true)
end sub

sub pushData()
    if m.home = invalid then return
    m.home.data = { hub: m.hub, state: m.state, channels: m.channels, server: m.server, mode: m.mode, tools: m.tools, search: m.search, searchSeq: m.searchSeq }
end sub

' Morning, day or night, by the hours set in Hub settings. Night dims the wallpaper.
sub computeMode()
    dt = CreateObject("roDateTime")
    dt.toLocalTime()
    h = dt.getHours()
    modes = m.hub.modes
    mode = "day"
    if modes <> invalid and isTrue(modes.auto)
        morning = 5
        night = 21
        if modes.morning <> invalid then morning = modes.morning
        if modes.night <> invalid then night = modes.night
        if h >= morning and h < 11
            mode = "morning"
        else if h >= night or h < morning
            mode = "night"
        end if
    end if
    if mode <> m.mode
        m.mode = mode
        o = 0.0
        if mode = "night" then o = 0.45
        animTo(m.top.findNode("night"), "opacity", o, "slow")
        pushData()
    end if
end sub

sub onHomeAction()
    a = m.home.action
    if a = invalid or a.kind = invalid then return
    m.idleSecs = 0
    k = a.kind
    if k = "channel"
        launchApp(a.id, invalid, "Opening " + a.name)
    else if k = "play"
        playTitle(a.item)
    else if k = "details"
        openApp("DetailsApp", { item: a.item, accent: m.hub.accent })
    else if k = "tool"
        openTool(a.id)
    else if k = "profile"
        openApp("ProfileApp", { profiles: m.state.profiles, accent: m.hub.accent })
    else if k = "search"
        askSearch()
    else if k = "video"
        launchApp(m.ytAppId, { "contentId": a.video.id, "mediaType": "movie" }, "Playing on YouTube")
    else if k = "sleep"
        showToast("The TV turns off in " + a.minutes.toStr() + " minutes")
        startRequest(serverUrl(m.server) + "/api/sleep", "POST", FormatJson({ "minutes": a.minutes }), "onLaunched")
    end if
end sub

' =====================================================================  wallpaper

sub setWallpaper(url as String)
    if url = m.wallUrl then return
    m.wallUrl = url
    b = m.top.findNode("wallB")
    b.opacity = 0
    b.uri = url
end sub

sub onWallLoaded()
    b = m.top.findNode("wallB")
    if b.loadStatus = "ready" and b.uri = m.wallUrl then animTo(b, "opacity", 1.0, "slow")
end sub

' =====================================================================  clock

sub onClock()
    dt = CreateObject("roDateTime")
    dt.toLocalTime()
    if m.home <> invalid then m.home.clock = hhmm(dt, isTrue(m.hub.clock24))
    if dt.getSeconds() = 0 then computeMode()
end sub

' =====================================================================  opening things

sub openTool(id as String)
    if m.server = "" and id <> "settings" and id <> "cast"
        askServer()
        return
    end if
    if id = "pick"
        openApp("RecommendApp", { accent: m.hub.accent, pick: m.state.pick })
    else if id = "ask"
        openApp("AskApp", { accent: m.hub.accent })
    else if id = "browser"
        openApp("BrowserApp", {})
    else if id = "library" or id = "music"
        openApp("LibraryApp", { start: id })
    else if id = "cast"
        showCastHelp()
    else if id = "settings"
        showSettings()
    end if
end sub

sub launchApp(appId as String, params as Dynamic, message as String)
    showToast(message)
    body = { "id": appId }
    if params <> invalid then body["params"] = params
    startRequest(serverUrl(m.server) + "/api/tv/launch", "POST", FormatJson(body), "onLaunched")
end sub

' Opens the show or movie itself in its app; the server knows each service's own IDs.
sub playTitle(item as Object)
    msg = "Opening " + item.title
    if txt(item.appName) <> "" then msg = msg + " on " + item.appName
    showToast(msg)
    startRequest(serverUrl(m.server) + "/api/title/play", "POST", FormatJson({ "item": item }), "onPlayed")
end sub

sub onPlayed(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    if not r.ok
        showToast("Couldn't open that. Check the Hub server is running.")
        return
    end if
    data = ParseJson(r.body)
    if data <> invalid and txt(data.how) = "search" and txt(data.app) = ""
        showToast("Searching your channels for it")
    end if
end sub

sub onLaunched(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok then showToast("Couldn't do that. Check the Hub server is running.")
end sub

sub openApp(name as String, params as Object)
    if m.current <> invalid then closeApp(false)
    app = CreateObject("roSGNode", name)
    if app = invalid then return
    app.server = m.server
    if params <> invalid then app.params = params
    app.observeField("done", "onAppDone")
    if app.hasField("action") then app.observeField("action", "onAppAction")
    m.appLayer.opacity = 0
    m.appLayer.appendChild(app)
    m.current = app
    animTo(m.appLayer, "opacity", 1.0, "smooth")
    animTo(m.homeLayer, "opacity", 0.0, "smooth")
    app.setFocus(true)
end sub

sub closeApp(animate as Boolean)
    if m.current = invalid then return
    m.current.unobserveField("done")
    if m.current.hasField("action") then m.current.unobserveField("action")
    if m.top.dialog <> invalid then m.top.dialog.close = true
    m.appLayer.removeChild(m.current)
    m.current = invalid
    m.idleSecs = 0
    if animate
        animTo(m.homeLayer, "opacity", 1.0, "smooth")
    else
        m.homeLayer.opacity = 1
    end if
    if m.home <> invalid then m.home.setFocus(true)
end sub

sub onAppDone()
    reload = m.current <> invalid and (m.current.subtype() = "ProfileApp" or m.current.subtype() = "DetailsApp")
    closeApp(true)
    if reload then loadState()
end sub

' Hub's screens can ask for things the frame does (open another screen, a toast).
sub onAppAction()
    a = m.current.action
    if a = invalid or a.kind = invalid then return
    if a.kind = "toast"
        showToast(a.text)
    else if a.kind = "details"
        openApp("DetailsApp", { item: a.item, accent: m.hub.accent })
    else if a.kind = "play"
        playTitle(a.item)
    end if
end sub

sub onLaunchParams()
    p = m.top.launchParams
    if p = invalid then return
    ' a launch link can carry the server address (used by the automated test run)
    if p.server <> invalid and p.server <> "" and p.server <> m.server
        m.server = p.server
        regWrite("browserServer", m.server)
        m.events.server = m.server
        loadState()
        loadChannels()
    end if
    if p.app = invalid then return
    closeDialog()
    if p.app = "browser"
        if m.current <> invalid and m.current.subtype() = "BrowserApp"
            m.current.refreshNow = true
        else
            openApp("BrowserApp", {})
        end if
    else if p.app = "player"
        openApp("PlayerApp", p)
    else if p.app = "library"
        openApp("LibraryApp", {})
    else if p.app = "pick"
        openTool("pick")
    else if p.app = "ask"
        openTool("ask")
    end if
end sub

' =====================================================================  server events

sub onServerEvent()
    ev = m.events.event
    if ev = invalid or ev.type = invalid then return
    if ev.type = "config" or ev.type = "widgets"
        loadState()
    else if ev.type = "toast"
        showToast(ev.text)
    else if ev.type = "ask"
        if m.current <> invalid and m.current.subtype() = "AskApp"
            m.current.remoteAsk = ev
        else
            openApp("AskApp", { accent: m.hub.accent, q: ev.q, answer: ev.answer, rec: ev.rec })
        end if
    else if ev.type = "pointer" or ev.type = "click" or ev.type = "scroll"
        if m.current <> invalid and m.current.subtype() = "BrowserApp" then m.current.remote = ev
    end if
end sub

sub onEventsConnected()
    if m.events.connected and not m.serverOk then loadState()
end sub

sub showToast(text as String)
    lbl = m.top.findNode("toastText")
    lbl.text = text
    w = lbl.boundingRect().width + 56
    bg = m.top.findNode("toastBg")
    bg.width = w
    bg.translation = [-w / 2, 0]
    lbl.width = w
    lbl.translation = [-w / 2, 0]
    t = m.top.findNode("toast")
    t.translation = [640, 8]
    animTo(t, "opacity", 1.0, "smooth")
    animTo(t, "translation", [640, 28], "bouncy")
    m.toastTimer.control = "stop"
    m.toastTimer.control = "start"
end sub

sub onToastTimer()
    animTo(m.top.findNode("toast"), "opacity", 0.0, "gentle")
end sub

' =====================================================================  screensaver

sub onIdleTick()
    if m.current <> invalid or m.dialog <> invalid
        m.idleSecs = 0
        return
    end if
    m.idleSecs = m.idleSecs + 30
    mins = 5
    if m.hub.screensaver <> invalid and m.hub.screensaver.minutes <> invalid then mins = m.hub.screensaver.minutes
    if mins > 0 and m.idleSecs >= mins * 60 and m.server <> ""
        m.idleSecs = 0
        weather = invalid
        if m.state.widgets <> invalid then weather = m.state.widgets.weather
        openApp("AmbientApp", { weather: weather, clock24: isTrue(m.hub.clock24) })
    end if
end sub

' =====================================================================  dialogs

sub openDialog(dlg as Object, kind as String)
    m.dialog = dlg
    m.dialogKind = kind
    dlg.observeField("buttonSelected", "onDialogButton")
    dlg.observeField("wasClosed", "onDialogClosed")
    m.top.dialog = dlg
end sub

sub closeDialog()
    if m.dialog <> invalid
        m.dialog.unobserveField("buttonSelected")
        m.dialog.unobserveField("wasClosed")
        m.dialog.close = true
    end if
    m.dialog = invalid
    m.dialogKind = ""
    if m.current = invalid and m.home <> invalid then m.home.setFocus(true)
end sub

sub onDialogClosed()
    closeDialog()
end sub

sub showSettings()
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = "Settings"
    if m.server = ""
        dlg.message = ["No Hub server set yet.", "Enter your server's address to turn on your channels, picks, details, the browser and the remote app."]
    else
        dlg.message = [
            "Hub server: " + m.server,
            "Change wallpapers, shows, profiles and widgets from Hub Remote or " + serverUrl(m.server) + "."
        ]
    end if
    other = "Console"
    if m.homeTheme = "console" then other = "Hub"
    dlg.buttons = ["Switch to the " + other + " look", "Who's watching", "Set server address", "Reload everything", "Privacy and terms", "Remove Hub from this TV", "Close"]
    openDialog(dlg, "settings")
end sub

sub askServer()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Hub server address"
    dlg.message = ["Your server's IP address, e.g. 10.0.0.134"]
    dlg.text = m.server
    dlg.buttons = ["Save", "Cancel"]
    openDialog(dlg, "server")
end sub

sub askSearch()
    if m.server = ""
        askServer()
        return
    end if
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Search shows and movies"
    dlg.message = ["Hub looks it up and shows which of your channels have it."]
    dlg.buttons = ["Search", "Cancel"]
    openDialog(dlg, "search")
end sub

sub onSearch(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.results = invalid or data.results.count() = 0
        showToast("Nothing found. Try another spelling.")
        return
    end if
    m.search = data.results
    m.searchSeq = m.searchSeq + 1
    pushData()
end sub

sub setTheme(theme as String)
    m.hub.theme = theme
    buildHome()
    startRequest(serverUrl(m.server) + "/api/hub/config", "POST", FormatJson({ "theme": theme }), "onLaunched")
end sub

sub showLegal()
    if m.server = ""
        showToast("Set the Hub server address first")
        return
    end if
    startRequest(serverUrl(m.server) + "/api/legal", "GET", "", "onLegal")
end sub

sub onLegal(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid
        showToast("Couldn't load that. Check the Hub server.")
        return
    end if
    lines = []
    for each part in data.privacy
        lines.push(part[0] + ". " + part[1])
    end for
    for each part in data.terms
        lines.push(part[0] + ". " + part[1])
    end for
    lines.push("Updated " + data.updated + ".")
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = "Privacy and terms"
    dlg.message = lines
    dlg.buttons = ["Close"]
    openDialog(dlg, "legal")
end sub

' Deleting a sideloaded channel goes through the TV's developer installer, which needs the
' password you set when you turned on developer mode. The Hub server sends the request.
sub askRemove()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Remove Hub from this TV"
    dlg.message = ["Type your TV's developer password. Hub closes and disappears from the home screen. To remove the Hub server and its data too, use Remove Hub on the control page."]
    editBox = dlg.textEditBox
    if editBox <> invalid then editBox.secureMode = true
    dlg.buttons = ["Remove Hub", "Cancel"]
    openDialog(dlg, "remove")
end sub

sub onRemoved(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    if not r.ok
        data = ParseJson(r.body)
        err = "Couldn't remove Hub."
        if data <> invalid and data.error <> invalid then err = data.error
        showToast(err)
    end if
end sub

sub showCastHelp()
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = "Cast your PC"
    dlg.message = [
        "This TV supports screen mirroring (Miracast).",
        "On Pop!_OS: install GNOME Network Displays from the app store, open it, and pick this TV.",
        "Accept the request when it shows up here. For a photo, video or link, use the Send tab in Hub Remote."
    ]
    dlg.buttons = ["Got it"]
    openDialog(dlg, "cast")
end sub

sub onDialogButton()
    kind = m.dialogKind
    idx = m.dialog.buttonSelected
    text = ""
    if kind = "server" or kind = "remove" or kind = "search" then text = m.dialog.text.trim()
    closeDialog()
    if kind = "settings"
        if idx = 0
            if m.homeTheme = "console"
                setTheme("hub")
            else
                setTheme("console")
            end if
        else if idx = 1
            openApp("ProfileApp", { profiles: m.state.profiles, accent: m.hub.accent })
        else if idx = 2
            askServer()
        else if idx = 3
            loadChannels()
            loadState()
        else if idx = 4
            showLegal()
        else if idx = 5
            askRemove()
        end if
    else if kind = "search" and idx = 0 and text <> ""
        showToast("Searching for " + text)
        startRequest(serverUrl(m.server) + "/api/shows/search?q=" + urlEncode(text, false), "GET", "", "onSearch")
    else if kind = "remove" and idx = 0
        if m.server = ""
            showToast("Set the Hub server address first")
        else
            showToast("Removing Hub...")
            startRequest(serverUrl(m.server) + "/api/tv/remove-channel", "POST", FormatJson({ "password": text }), "onRemoved")
        end if
    else if kind = "server" and idx = 0
        m.server = text
        regWrite("browserServer", text)
        m.events.server = text
        m.serverOk = false
        loadState()
        loadChannels()
        pushData()
    end if
end sub

' =====================================================================  remote

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    m.idleSecs = 0
    if m.current <> invalid
        if key = "back"
            closeApp(true)
            return true
        end if
        return false
    end if
    if key = "options"
        showSettings()
        return true
    end if
    return false
end function
