' Hub 3 home screen.
' Wallpaper + header (clock, greeting, weather and sleep chips) + a vertical rail of rows:
' widgets, YouTube, anime, Hub apps, channels. Rows, widgets, wallpaper and accent come
' from the Hub server and can be changed from the phone/tablet/PC.
'
' To add your own app: make a component that extends Group with `done` (boolean) and
' `server` (string) fields, then add it to m.hubApps.

sub init()
    m.hubApps = [
        { title: "Browser", icon: "pkg:/images/globe.png", color: "0x3B6CF6FF", kind: "app", component: "BrowserApp" },
        { title: "Library", icon: "pkg:/images/library.png", color: "0x10956FFF", kind: "app", component: "LibraryApp" },
        { title: "Cast your PC", icon: "pkg:/images/cast.png", color: "0x7547D6FF", kind: "cast" },
        { title: "Settings", icon: "pkg:/images/gear.png", color: "0x2A3347FF", kind: "settings" }
    ]
    m.server = regRead("browserServer", "")
    m.tvIp = deviceIp()
    m.hub = { accent: "#4F7CFF", clock24: false, name: "", rows: [], widgets: [], favorites: [], screensaver: { minutes: 5 } }
    m.widgetData = {}
    m.sleepLeft = invalid
    m.sleepStamp = 0
    m.serverOk = false
    m.wallUrl = ""
    m.channels = []
    m.channelError = false
    m.noteIndex = 0
    m.rows = []
    m.rowIndex = 0
    m.focusedCard = invalid
    m.shrinking = invalid
    m.current = invalid
    m.dialog = invalid
    m.dialogKind = ""
    m.pressing = false
    m.idleSecs = 0
    m.ytAppId = "837"
    m.crAppId = "2595"

    m.fTime = makeFont(true, 76)
    m.fGreet = makeFont(true, 26)
    m.fDate = makeFont(false, 20)
    m.fRow = makeFont(true, 24)
    m.fCaption = makeFont(true, 22)
    m.fTitle = makeFont(true, 24)
    m.fBody = makeFont(false, 19)
    m.fSmall = makeFont(false, 16)
    m.fSmallBold = makeFont(true, 16)
    m.fTemp = makeFont(true, 62)
    m.fNote = makeFont(true, 22)
    m.fChip = makeFont(false, 18)

    m.top.findNode("time").font = m.fTime
    m.top.findNode("greet").font = m.fGreet
    m.top.findNode("date").font = m.fDate
    m.top.findNode("toastText").font = m.fChip

    m.rail = m.top.findNode("rail")
    m.home = m.top.findNode("home")
    m.appLayer = m.top.findNode("appLayer")
    m.focusAnim = m.top.findNode("focusAnim")
    m.growInterp = m.top.findNode("growInterp")
    m.shrinkInterp = m.top.findNode("shrinkInterp")
    m.glowIn = m.top.findNode("glowIn")
    m.glowOut = m.top.findNode("glowOut")
    m.ringIn = m.top.findNode("ringIn")
    m.pressAnim = m.top.findNode("pressAnim")
    m.pressInterp = m.top.findNode("pressInterp")
    m.pressAnim.observeField("state", "onPressDone")
    m.scrollAnim = m.top.findNode("scrollAnim")
    m.scrollInterp = m.top.findNode("scrollInterp")
    m.railAnim = m.top.findNode("railAnim")
    m.railInterp = m.top.findNode("railInterp")
    m.captionAnim = m.top.findNode("captionAnim")
    m.captionInterp = m.top.findNode("captionInterp")
    m.wallAnim = m.top.findNode("wallAnim")
    m.wallAnim.observeField("state", "onWallDone")
    m.top.findNode("wallB").observeField("loadStatus", "onWallLoaded")

    clock = m.top.findNode("clockTimer")
    clock.observeField("fire", "onClock")
    clock.control = "start"
    onClock()
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

    buildRows()
    loadState()
    loadChannels()
    renderChips()
    m.top.setFocus(true)
    m.top.findNode("introAnim").control = "start"
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
        if m.serverOk then m.serverOk = false
        renderChips()
        return
    end if
    m.serverOk = true
    m.hub = data.hub
    m.widgetData = data.widgets
    m.sleepLeft = data.sleep
    m.sleepStamp = UpTime(0)
    setWallpaper(absUrl(m.server, data.wallpaper))
    sortChannels()
    onClock()
    renderChips()
    buildRows()
    ' the server was unreachable when Hub opened: fetch the channels now
    if m.channels.count() = 0 then loadChannels()
end sub

' Roku OS 12+ blocks a channel from using the TV's control port (8060) on its own
' device, so the channel list, icons and launches go through the Hub server, which
' reaches the TV from outside. Without a server we try the TV directly (older OS).
sub loadChannels()
    if m.server <> ""
        startRequest(serverUrl(m.server) + "/api/tv/apps", "GET", "", "onApps")
    else if m.tvIp <> ""
        startRequest("http://" + m.tvIp + ":8060/query/apps", "GET", "", "onApps")
    end if
end sub

function channelIcon(id as String) as String
    if m.server <> "" then return serverUrl(m.server) + "/api/tv/icon/" + urlEncode(id, false)
    return "http://" + m.tvIp + ":8060/query/icon/" + id
end function

sub noteChannel(id as String, name as String)
    m.channels.push({ id: id, name: name })
    lname = LCase(name)
    if lname = "youtube" then m.ytAppId = id
    if Instr(1, lname, "crunchyroll") > 0 then m.crAppId = id
end sub

sub onApps(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    m.channels = []
    m.channelError = false
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data <> invalid and data.apps <> invalid
        ' from the Hub server: {apps: [{id, name, fav}]}
        for each a in data.apps
            noteChannel(a.id, a.name)
        end for
    else
        xml = CreateObject("roXMLElement")
        if r.ok and xml.parse(r.body)
            for each app in xml.getNamedElements("app")
                attrs = app.getAttributes()
                if attrs["type"] = "appl" and attrs["id"] <> "dev" then noteChannel(attrs["id"], app.getText())
            end for
        else
            m.channelError = true
        end if
    end if
    sortChannels()
    buildRows()
end sub

sub sortChannels()
    favs = m.hub.favorites
    if favs = invalid or m.channels.count() = 0 then return
    first = []
    rest = []
    for each f in favs
        for each c in m.channels
            if c.id = f then first.push(c)
        end for
    end for
    for each c in m.channels
        isFav = false
        for each f in favs
            if c.id = f then isFav = true
        end for
        if not isFav then rest.push(c)
    end for
    first.append(rest)
    m.channels = first
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
    if b.loadStatus = "ready" and b.uri = m.wallUrl
        m.wallAnim.control = "start"
    end if
end sub

sub onWallDone()
    if m.wallAnim.state <> "stopped" then return
    a = m.top.findNode("wallA")
    b = m.top.findNode("wallB")
    if b.opacity < 0.99 then return
    a.uri = b.uri
    b.opacity = 0
end sub

' =====================================================================  header

sub onClock()
    dt = CreateObject("roDateTime")
    dt.toLocalTime()
    clock24 = m.hub.clock24 = true
    m.top.findNode("time").text = hhmm(dt, clock24)
    h = dt.getHours()
    greeting = "Good evening"
    if h < 5
        greeting = "Good night"
    else if h < 12
        greeting = "Good morning"
    else if h < 17
        greeting = "Good afternoon"
    end if
    if m.hub.name <> invalid and m.hub.name <> "" then greeting = greeting + ", " + m.hub.name
    m.top.findNode("greet").text = greeting
    months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
    m.top.findNode("date").text = dt.getWeekday() + ", " + months[dt.getMonth() - 1] + " " + dt.getDayOfMonth().toStr()
    if dt.getSeconds() = 0 then renderChips()
end sub

' Right-aligned pills in the header: weather, sleep timer, server status.
sub renderChips()
    chips = m.top.findNode("chips")
    chips.removeChildrenIndex(chips.getChildCount(), 0)
    items = []
    w = m.widgetData.weather
    if w <> invalid
        items.push({ icon: "pkg:/images/w_" + w.icon + ".png", text: w.temp.toStr() + Chr(176) + "  " + w.text })
    end if
    if m.sleepLeft <> invalid
        secsLeft = m.sleepLeft - (UpTime(0) - m.sleepStamp)
        if secsLeft > 0 then items.push({ icon: "pkg:/images/moon.png", text: "Off " + untilText(secsLeft) })
    end if
    if m.server = ""
        items.push({ dot: "0x8A95ADFF", text: "Set up the server in Settings" })
    else if not m.serverOk
        items.push({ dot: "0xFF6B6BFF", text: "Server offline" })
    end if
    x = 0
    for i = items.count() - 1 to 0 step -1
        it = items[i]
        g = CreateObject("roSGNode", "Group")
        lbl = CreateObject("roSGNode", "Label")
        lbl.font = m.fChip
        lbl.color = "0xFFFFFFFF"
        lbl.text = it.text
        lbl.height = 46
        lbl.vertAlign = "center"
        tw = lbl.boundingRect().width
        padL = 18
        if it.icon <> invalid
            padL = 52
        else if it.dot <> invalid
            padL = 36
        end if
        pw = tw + padL + 20
        x = x - pw
        bg = g.createChild("Poster")
        bg.uri = "pkg:/images/pill.9.png"
        bg.width = pw
        bg.height = 46
        bg.blendColor = "0x0B0F1EB0"
        if it.icon <> invalid
            ic = g.createChild("Poster")
            ic.uri = it.icon
            ic.width = 30
            ic.height = 30
            ic.translation = [14, 8]
        else if it.dot <> invalid
            dot = g.createChild("Poster")
            dot.uri = "pkg:/images/dot.png"
            dot.width = 10
            dot.height = 10
            dot.translation = [18, 18]
            dot.blendColor = it.dot
        end if
        lbl.translation = [padL, 0]
        g.appendChild(lbl)
        g.translation = [x, 0]
        chips.appendChild(g)
        x = x - 12
    end for
end sub

' =====================================================================  rows

function rowEnabled(id as String) as Boolean
    rows = m.hub.rows
    if rows = invalid or rows.count() = 0 then return true
    for each r in rows
        if r.id = id then return r.on = true
    end for
    return false
end function

function rowOrder() as Object
    order = []
    if m.hub.rows <> invalid
        for each r in m.hub.rows
            order.push(r.id)
        end for
    end if
    if order.count() = 0 then order = ["widgets", "youtube", "anime", "apps", "channels"]
    return order
end function

sub buildRows()
    ' remember focus so a refresh doesn't jump
    keepId = ""
    keepCol = 0
    if m.rows.count() > 0 and m.rowIndex < m.rows.count()
        keepId = m.rows[m.rowIndex].id
        keepCol = m.rows[m.rowIndex].col
    end if

    m.focusAnim.control = "stop"
    m.scrollAnim.control = "stop"
    m.railAnim.control = "stop"
    m.focusedCard = invalid
    m.shrinking = invalid
    m.rail.removeChildrenIndex(m.rail.getChildCount(), 0)
    m.rows = []
    y = 0
    for each id in rowOrder()
        if rowEnabled(id)
            row = makeRow(id)
            if row <> invalid
                row.y = y
                row.group.translation = [0, y]
                m.rail.appendChild(row.group)
                m.rows.push(row)
                y = y + row.height
            end if
        end if
    end for
    if m.rows.count() = 0 then return

    m.rowIndex = 0
    for i = 0 to m.rows.count() - 1
        if m.rows[i].id = keepId then m.rowIndex = i
    end for
    row = m.rows[m.rowIndex]
    col = keepCol
    if col > row.cards.count() - 1 then col = row.cards.count() - 1
    m.rail.translation = [0, 196 - row.y]
    focusCard(m.rowIndex, col, false)
end sub

function newRow(id as String, title as String, cardH as Integer) as Object
    g = CreateObject("roSGNode", "Group")
    g.id = "row_" + id
    t = g.createChild("Label")
    t.text = title
    t.font = m.fRow
    t.color = "0xFFFFFFE6"
    t.translation = [80, 0]
    clip = g.createChild("Group")
    clip.translation = [80, 46]
    clip.clippingRect = [-80, -70, 1280, cardH + 140]
    cards = clip.createChild("Group")
    cards.id = "cards_" + id
    cap = g.createChild("Label")
    cap.id = "cap_" + id
    cap.font = m.fCaption
    cap.color = "0xFFFFFFFF"
    cap.width = 1100
    cap.translation = [80, 46 + cardH + 18]
    cap.opacity = 0
    return { id: id, group: g, cardsGroup: cards, caption: cap, cards: [], xs: [], ws: [], captions: [], kinds: [], col: 0, offset: 0, cardH: cardH, height: 46 + cardH + 76 }
end function

sub addCard(row as Object, card as Object, w as Integer, caption as String)
    x = 0
    n = row.cards.count()
    if n > 0 then x = row.xs[n - 1] + row.ws[n - 1] + 26
    card.translation = [x, 0]
    row.cardsGroup.appendChild(card)
    row.cards.push(card)
    row.xs.push(x)
    row.ws.push(w)
    row.captions.push(caption)
end sub

function makeRow(id as String) as Object
    if id = "widgets" then return widgetRow()
    if id = "youtube" then return youtubeRow()
    if id = "anime" then return animeRow()
    if id = "apps" then return appsRow()
    if id = "channels" then return channelsRow()
    return invalid
end function

' -- card shell: 0 glow, 1 focus ring, 2 face, then content
function makeCard(row as Object, w as Integer, h as Integer, faceColor as String) as Object
    g = CreateObject("roSGNode", "Group")
    g.id = "c_" + row.id + "_" + row.cards.count().toStr()
    g.scaleRotateCenter = [w / 2, h / 2]
    pad = 48
    glow = g.createChild("Poster")
    glow.id = g.id + "_glow"
    glow.uri = "pkg:/images/glow.png"
    glow.width = w + pad * 2
    glow.height = h + pad * 2
    glow.translation = [-pad, -pad + 10]
    glow.blendColor = rokuColor(m.hub.accent, "FF")
    glow.opacity = 0
    ring = g.createChild("Poster")
    ring.id = g.id + "_ring"
    ring.uri = "pkg:/images/ring.9.png"
    ring.width = w + 18
    ring.height = h + 18
    ring.translation = [-9, -9]
    ring.opacity = 0
    face = g.createChild("Poster")
    face.uri = "pkg:/images/card.9.png"
    face.width = w
    face.height = h
    face.blendColor = faceColor
    return g
end function

function addLabel(parent as Object, text as String, x as Integer, y as Integer, w as Integer, font as Object, color as String) as Object
    lbl = parent.createChild("Label")
    lbl.translation = [x, y]
    lbl.width = w
    lbl.font = font
    lbl.color = color
    lbl.text = text
    return lbl
end function

function addImage(parent as Object, uri as String, x as Integer, y as Integer, w as Integer, h as Integer) as Object
    p = parent.createChild("Poster")
    p.uri = uri
    p.translation = [x, y]
    p.width = w
    p.height = h
    return p
end function

' Rounded artwork (thumbnails, covers, channel icons) with an optional bottom shade for text.
function addArt(card as Object, uri as String, maskUri as String, w as Integer, h as Integer, shade as Boolean) as Object
    mask = card.createChild("MaskGroup")
    mask.maskUri = maskUri
    mask.maskSize = [w, h]
    art = mask.createChild("Poster")
    art.uri = uri
    art.width = w
    art.height = h
    art.loadDisplayMode = "scaleToFill"
    if shade
        sh = mask.createChild("Poster")
        sh.uri = "pkg:/images/shade.png"
        sh.width = w
        sh.height = Int(h * 0.62)
        sh.translation = [0, h - Int(h * 0.62)]
    end if
    return mask
end function

' -- widgets row
function widgetRow() as Object
    row = newRow("widgets", "Today", 210)
    list = m.hub.widgets
    if list = invalid or list.count() = 0 then list = [{ id: "weather", on: true }, { id: "agenda", on: true }, { id: "notes", on: true }]
    for each wd in list
        if wd.on = true
            if wd.id = "weather" then weatherCard(row)
            if wd.id = "agenda" then agendaCard(row)
            if wd.id = "notes" then notesCard(row)
            while row.kinds.count() < row.cards.count()
                row.kinds.push(wd.id)
            end while
        end if
    end for
    if row.cards.count() = 0 then return invalid
    return row
end function

sub weatherCard(row as Object)
    w = 400
    card = makeCard(row, w, 210, "0x0B1022C8")
    wd = m.widgetData.weather
    if wd = invalid
        addLabel(card, "Weather", 24, 20, w - 48, m.fTitle, "0xFFFFFFFF")
        msg = addLabel(card, "Set your location on the Hub page of the remote app or control page.", 24, 62, w - 48, m.fBody, "0xFFFFFFB0")
        msg.wrap = true
    else
        addImage(card, "pkg:/images/w_" + wd.icon + ".png", 18, 14, 92, 92)
        addLabel(card, wd.temp.toStr() + Chr(176), 122, 14, 160, m.fTemp, "0xFFFFFFFF")
        addLabel(card, wd.text, 124, 88, w - 140, m.fBody, "0xFFFFFFE0")
        addLabel(card, wd.place + ", feels like " + wd.feels.toStr() + Chr(176), 24, 118, w - 48, m.fSmall, "0xFFFFFF99")
        x = 24
        for i = 1 to 4
            if i < wd.days.count()
                d = wd.days[i]
                addLabel(card, d.day, x, 150, 84, m.fSmallBold, "0xFFFFFFC8")
                addImage(card, "pkg:/images/w_" + d.icon + ".png", x + 40, 146, 28, 28)
                addLabel(card, d.hi.toStr() + Chr(176) + " / " + d.lo.toStr() + Chr(176), x, 176, 90, m.fSmall, "0xFFFFFF99")
                x = x + 92
            end if
        end for
    end if
    addCard(row, card, w, "")
end sub

sub agendaCard(row as Object)
    w = 400
    card = makeCard(row, w, 210, "0x0B1022C8")
    addLabel(card, "Up next", 24, 18, w - 48, m.fTitle, "0xFFFFFFFF")
    list = m.widgetData.agenda
    if list = invalid or list.count() = 0
        empty = addLabel(card, "Nothing coming up. Add events or your calendar link from the remote.", 24, 60, w - 48, m.fBody, "0xFFFFFFA0")
        empty.wrap = true
    else
        y = 58
        n = list.count()
        if n > 3 then n = 3
        clock24 = m.hub.clock24 = true
        for i = 0 to n - 1
            e = list[i]
            addLabel(card, dayAndTime(e.start, clock24, e.allDay = true), 24, y, w - 48, m.fSmallBold, rokuColor(m.hub.accent, "FF"))
            addLabel(card, e.title, 24, y + 20, w - 48, m.fBody, "0xFFFFFFE6")
            y = y + 48
        end for
    end if
    addCard(row, card, w, "")
end sub

sub notesCard(row as Object)
    w = 300
    notes = m.widgetData.notes
    if notes = invalid or notes.count() = 0
        card = makeCard(row, w, 210, "0x0B1022C8")
        addLabel(card, "Notes", 24, 18, w - 48, m.fTitle, "0xFFFFFFFF")
        empty = addLabel(card, "No notes yet. Post one from your phone and it shows up here.", 24, 60, w - 48, m.fBody, "0xFFFFFFA0")
        empty.wrap = true
    else
        if m.noteIndex >= notes.count() then m.noteIndex = 0
        n = notes[m.noteIndex]
        card = makeCard(row, w, 210, rokuColor(n.color, "FF"))
        txt = addLabel(card, n.text, 22, 20, w - 44, m.fNote, "0x1D1A10FF")
        txt.wrap = true
        txt.maxLines = 5
        footer = "Note"
        if notes.count() > 1 then footer = "Note " + (m.noteIndex + 1).toStr() + " of " + notes.count().toStr() + ", OK for next"
        addLabel(card, footer, 22, 176, w - 44, m.fSmall, "0x1D1A10AA")
    end if
    addCard(row, card, w, "")
end sub

' -- YouTube row
function youtubeRow() as Object
    vids = m.widgetData.youtube
    if vids = invalid or vids.count() = 0 then return invalid
    row = newRow("youtube", "New on YouTube", 180)
    n = vids.count()
    if n > 14 then n = 14
    for i = 0 to n - 1
        v = vids[i]
        card = makeCard(row, 320, 180, "0xFFFFFF14")
        addArt(card, v.thumb, "pkg:/images/mask_thumb.png", 320, 180, true)
        t = addLabel(card, v.title, 16, 104, 288, m.fSmallBold, "0xFFFFFFFF")
        t.wrap = true
        t.maxLines = 2
        addLabel(card, v.channel, 16, 150, 288, m.fSmall, "0xFFFFFFB0")
        addCard(row, card, 320, v.title)
    end for
    return row
end function

' -- anime row
function animeRow() as Object
    list = m.widgetData.anime
    if list = invalid or list.count() = 0 then return invalid
    row = newRow("anime", "Anime episodes", 212)
    now = CreateObject("roDateTime").asSeconds()
    for each a in list
        card = makeCard(row, 150, 212, "0xFFFFFF14")
        addArt(card, a.cover, "pkg:/images/mask_cover.png", 150, 212, true)
        sub1 = ""
        sub2 = ""
        if a.episode <> invalid
            sub1 = "Ep " + a.episode.toStr()
            sub2 = untilText(a.airingAt - now)
        else if a.status <> invalid
            sub1 = LCase(a.status)
        end if
        addLabel(card, sub1, 12, 158, 128, m.fSmallBold, "0xFFFFFFFF")
        addLabel(card, sub2, 12, 182, 128, m.fSmall, "0xFFFFFFC0")
        caption = a.title
        if a.airingAt <> invalid then caption = a.title + "   " + dayAndTime(a.airingAt, m.hub.clock24 = true, false)
        addCard(row, card, 150, caption)
    end for
    return row
end function

' -- Hub apps row
function appsRow() as Object
    row = newRow("apps", "Hub", 132)
    for each a in m.hubApps
        card = makeCard(row, 232, 132, a.color)
        addImage(card, a.icon, 18, 18, 46, 46)
        addLabel(card, a.title, 18, 82, 200, m.fTitle, "0xFFFFFFFF")
        addCard(row, card, 232, "")
    end for
    return row
end function

' -- channels row
function channelsRow() as Object
    row = newRow("channels", "Channels", 159)
    if m.channels.count() = 0
        card = makeCard(row, 720, 159, "0x0B1022C8")
        text = "Loading your channels..."
        if m.channelError = true
            if m.server = ""
                text = "Set your Hub server in Settings to see your channels here. (Roku doesn't let a channel list them by itself.)"
            else
                text = "Can't get your channels from the Hub server. Check that it's running and that its TV address is right (Hub tab in the remote app). Press OK to try again."
            end if
        end if
        msg = addLabel(card, text, 28, 0, 664, m.fBody, "0xFFFFFFC8")
        msg.height = 159
        msg.vertAlign = "center"
        msg.wrap = true
        card.scaleRotateCenter = [360, 80]
        addCard(row, card, 720, "")
        return row
    end if
    for each c in m.channels
        card = makeCard(row, 212, 159, "0xFFFFFF1A")
        addArt(card, channelIcon(c.id), "pkg:/images/iconmask.png", 212, 159, false)
        addCard(row, card, 212, c.name)
    end for
    return row
end function

' =====================================================================  focus

sub focusCard(rowIdx as Integer, col as Integer, animate as Boolean)
    row = m.rows[rowIdx]
    if col < 0 or col >= row.cards.count() then return
    card = row.cards[col]

    m.focusAnim.control = "stop"
    if m.shrinking <> invalid
        m.shrinking.scale = [1.0, 1.0]
        m.shrinking.getChild(0).opacity = 0
        m.shrinking.getChild(1).opacity = 0
    end if
    m.shrinking = invalid
    m.shrinkInterp.fieldToInterp = "dummy.scale"
    m.glowOut.fieldToInterp = "dummy.opacity"
    if m.focusedCard <> invalid
        if not m.focusedCard.isSameNode(card)
            m.focusedCard.getChild(1).opacity = 0
            m.shrinking = m.focusedCard
            m.shrinkInterp.fieldToInterp = m.focusedCard.id + ".scale"
            m.glowOut.fieldToInterp = m.focusedCard.id + "_glow.opacity"
        end if
    end if
    m.growInterp.fieldToInterp = card.id + ".scale"
    m.glowIn.fieldToInterp = card.id + "_glow.opacity"
    m.ringIn.fieldToInterp = card.id + "_ring.opacity"
    m.focusAnim.control = "start"

    ' caption under the row
    rowChanged = rowIdx <> m.rowIndex
    if rowChanged or col <> row.col or m.focusedCard = invalid
        if rowIdx < m.rows.count() and m.rowIndex < m.rows.count() and rowChanged
            m.rows[m.rowIndex].caption.opacity = 0
        end if
        row.caption.text = row.captions[col]
        if row.captions[col] <> ""
            m.captionAnim.control = "stop"
            m.captionInterp.fieldToInterp = row.caption.id + ".opacity"
            m.captionAnim.control = "start"
        else
            row.caption.opacity = 0
        end if
    end if

    m.focusedCard = card
    m.rowIndex = rowIdx
    row.col = col
    scrollRowTo(row, col, animate)
    scrollRailTo(rowIdx, animate)
end sub

sub scrollRowTo(row as Object, col as Integer, animate as Boolean)
    x = row.xs[col]
    w = row.ws[col]
    target = row.offset
    visible = 1120
    if x + target > visible - w then target = (visible - w) - x
    if x + target < 0 then target = -x
    if target = row.offset then return
    row.offset = target
    if animate
        m.scrollAnim.control = "stop"
        m.scrollInterp.fieldToInterp = row.cardsGroup.id + ".translation"
        m.scrollInterp.keyValue = [row.cardsGroup.translation, [target, 0]]
        m.scrollAnim.control = "start"
    else
        row.cardsGroup.translation = [target, 0]
    end if
end sub

sub scrollRailTo(rowIdx as Integer, animate as Boolean)
    target = [0, 196 - m.rows[rowIdx].y]
    if animate
        m.railAnim.control = "stop"
        m.railInterp.keyValue = [m.rail.translation, target]
        m.railAnim.control = "start"
    else
        m.rail.translation = target
    end if
end sub

sub moveRow(delta as Integer)
    n = m.rowIndex + delta
    if n < 0 or n >= m.rows.count() then return
    cur = m.rows[m.rowIndex]
    target = m.rows[n]
    ' keep roughly the same horizontal spot
    x = cur.xs[cur.col] + cur.offset + cur.ws[cur.col] / 2
    best = target.col
    bestD = 99999
    for i = 0 to target.cards.count() - 1
        d = Abs(target.xs[i] + target.offset + target.ws[i] / 2 - x)
        if d < bestD
            bestD = d
            best = i
        end if
    end for
    focusCard(n, best, true)
end sub

' =====================================================================  actions

sub pressFocused()
    if m.pressing or m.focusedCard = invalid then return
    m.pressing = true
    m.focusAnim.control = "finish"
    m.pressInterp.fieldToInterp = m.focusedCard.id + ".scale"
    m.pressAnim.control = "start"
end sub

sub onPressDone()
    if m.pressAnim.state <> "stopped" or not m.pressing then return
    m.pressing = false
    m.pressInterp.fieldToInterp = "dummy.scale"
    activate()
end sub

sub activate()
    row = m.rows[m.rowIndex]
    col = row.col
    if row.id = "apps"
        a = m.hubApps[col]
        if a.kind = "app"
            openApp(a.component, {})
        else if a.kind = "cast"
            showCastHelp()
        else if a.kind = "settings"
            showSettings()
        end if
    else if row.id = "channels"
        if m.channels.count() = 0
            loadChannels()
        else
            ecpLaunch(m.channels[col].id, invalid, "Opening " + m.channels[col].name)
        end if
    else if row.id = "youtube"
        v = m.widgetData.youtube[col]
        ecpLaunch(m.ytAppId, { "contentId": v.id, "mediaType": "movie" }, "Playing on YouTube")
    else if row.id = "anime"
        ecpLaunch(m.crAppId, invalid, "Opening Crunchyroll")
    else if row.id = "widgets"
        isNotes = false
        if col < row.kinds.count() then isNotes = row.kinds[col] = "notes"
        if isNotes and m.widgetData.notes <> invalid and m.widgetData.notes.count() > 1
            ' OK on the notes card shows the next note
            m.noteIndex = m.noteIndex + 1
            buildRows()
        else
            loadState()
        end if
    end if
end sub

sub ecpLaunch(appId as String, params as Dynamic, message as String)
    showToast(message)
    if m.server <> ""
        body = { "id": appId }
        if params <> invalid then body["params"] = params
        startRequest(serverUrl(m.server) + "/api/tv/launch", "POST", FormatJson(body), "onLaunched")
    else
        query = ""
        if params <> invalid
            for each k in params
                if query = ""
                    query = "?"
                else
                    query = query + "&"
                end if
                query = query + k + "=" + urlEncode(params[k], false)
            end for
        end if
        startRequest("http://" + m.tvIp + ":8060/launch/" + appId + query, "POST", "", "onLaunched")
    end if
end sub

sub onLaunched(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok then showToast("Couldn't open that. Check the Hub server is running.")
end sub

' =====================================================================  apps

sub openApp(name as String, params as Object)
    if m.current <> invalid then closeApp(false)
    app = CreateObject("roSGNode", name)
    if app = invalid then return
    app.server = m.server
    if params <> invalid then app.params = params
    app.observeField("done", "onAppDone")
    m.appLayer.opacity = 0
    m.appLayer.appendChild(app)
    m.current = app
    m.top.findNode("openAnim").control = "start"
    app.setFocus(true)
end sub

sub closeApp(animate as Boolean)
    if m.current = invalid then return
    m.current.unobserveField("done")
    if m.top.dialog <> invalid then m.top.dialog.close = true
    m.appLayer.removeChild(m.current)
    m.current = invalid
    m.top.findNode("openAnim").control = "stop"
    m.home.opacity = 1
    m.idleSecs = 0
    onClock()
    if animate then m.top.findNode("closeAnim").control = "start"
    m.top.setFocus(true)
end sub

sub onAppDone()
    closeApp(true)
end sub

sub onLaunchParams()
    p = m.top.launchParams
    if p = invalid or p.app = invalid then return
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
    a = m.top.findNode("toastAnim")
    a.control = "stop"
    a.control = "start"
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
        openApp("AmbientApp", { weather: m.widgetData.weather, clock24: m.hub.clock24 = true })
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
    if m.current = invalid then m.top.setFocus(true)
end sub

sub onDialogClosed()
    closeDialog()
end sub

sub showSettings()
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = "Settings"
    if m.server = ""
        dlg.message = ["No Hub server set yet.", "Enter your CasaOS server's address to turn on the browser, widgets, wallpapers and the remote app."]
    else
        dlg.message = [
            "Hub server: " + m.server,
            "Control and customize Hub from any phone or PC at " + serverUrl(m.server) + ", or with the Hub Remote app."
        ]
    end if
    dlg.buttons = ["Set server address", "Reload everything", "Close"]
    openDialog(dlg, "settings")
end sub

sub askServer()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Hub server address"
    dlg.message = ["Your CasaOS server's IP address, e.g. 10.0.0.134"]
    dlg.text = m.server
    dlg.buttons = ["Save", "Cancel"]
    openDialog(dlg, "server")
end sub

sub showCastHelp()
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = "Cast your PC"
    dlg.message = [
        "This TV supports screen mirroring (Miracast).",
        "On Pop!_OS: install GNOME Network Displays from the app store, open it, and pick this TV.",
        "Accept the request when it shows up here. To send just a photo, video or link, use Send in the Hub Remote app."
    ]
    dlg.buttons = ["Got it"]
    openDialog(dlg, "cast")
end sub

sub onDialogButton()
    kind = m.dialogKind
    idx = m.dialog.buttonSelected
    text = ""
    if kind = "server" then text = m.dialog.text.trim()
    closeDialog()
    if kind = "settings"
        if idx = 0
            askServer()
        else if idx = 1
            loadChannels()
            loadState()
        end if
    else if kind = "server" and idx = 0
        m.server = text
        regWrite("browserServer", text)
        m.events.server = text
        m.serverOk = false
        loadState()
        loadChannels()
        renderChips()
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
    if m.pressing or m.rows.count() = 0 then return true

    row = m.rows[m.rowIndex]
    if key = "left"
        if row.col > 0 then focusCard(m.rowIndex, row.col - 1, true)
    else if key = "right"
        if row.col < row.cards.count() - 1 then focusCard(m.rowIndex, row.col + 1, true)
    else if key = "down"
        moveRow(1)
    else if key = "up"
        moveRow(-1)
    else if key = "OK"
        pressFocused()
    else if key = "options"
        showSettings()
    else if key = "back"
        if m.rowIndex > 0 then moveRow(-m.rowIndex)
    else
        return false
    end if
    return true
end function
