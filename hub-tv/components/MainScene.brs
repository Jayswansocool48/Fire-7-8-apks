' Hub home screen.
'   Top: photo wallpaper, clock, date, weather and what-to-wear tip, the latest note
'        from your phone (drawn or typed), and a quote from your shows.
'   Bottom: a dock with your channels and Hub's own tools.
'   Press Down for the lower deck: Hub's pick for you and My Shows, YouTube, anime, Up next.
' Everything comes from the Hub server and can be changed from the phone, tablet or PC.

sub init()
    m.tools = [
        { id: "pick", name: "Recommend", icon: "pkg:/images/i_pick.png" },
        { id: "ask", name: "Ask", icon: "pkg:/images/i_ask.png" },
        { id: "browser", name: "Browser", icon: "pkg:/images/globe.png" },
        { id: "library", name: "Library", icon: "pkg:/images/library.png" },
        { id: "cast", name: "Cast your PC", icon: "pkg:/images/cast.png" },
        { id: "settings", name: "Settings", icon: "pkg:/images/gear.png" }
    ]
    m.server = regRead("browserServer", "")
    m.hub = { accent: "#D9894A", clock24: false, name: "", rows: [], widgets: [], favorites: [], screensaver: { minutes: 5 } }
    m.state = { widgets: {}, quotes: [], shows: [], pick: invalid }
    m.sleepLeft = invalid
    m.sleepStamp = 0
    m.serverOk = false
    m.wallUrl = ""
    m.channels = []
    m.channelError = false
    m.rows = []
    m.rowIndex = 0
    m.focusedCard = invalid
    m.shrinking = invalid
    m.cardScale = {}
    m.current = invalid
    m.dialog = invalid
    m.dialogKind = ""
    m.pressing = false
    m.idleSecs = 0
    m.ytAppId = "837"
    m.crAppId = "2595"
    m.quoteIndex = -1
    m.noteId = ""
    m.DOCK_W = 120
    m.DOCK_H = 90
    m.DOCK_GAP = 22
    m.RAIL_TOP = 760

    m.fRow = fontFile("title", 26)
    m.fCaption = fontFile("medium", 22)
    m.fTitle = fontFile("semibold", 24)
    m.fBody = fontFile("regular", 19)
    m.fSmall = fontFile("regular", 16)
    m.fSmallBold = fontFile("semibold", 16)

    m.top.findNode("clock").font = fontFile("clock", 148)
    m.top.findNode("ampm").font = fontFile("regular", 26)
    m.top.findNode("date").font = fontFile("medium", 26)
    m.top.findNode("wText").font = fontFile("regular", 22)
    m.top.findNode("tipText").font = fontFile("medium", 17)
    m.top.findNode("noteText").font = fontFile("semibold", 26)
    m.top.findNode("noteCaption").font = fontFile("regular", 15)
    m.top.findNode("quoteText").font = fontFile("quote", 36)
    m.top.findNode("quoteBy").font = fontFile("regular", 19)
    m.top.findNode("dockLabel").font = fontFile("medium", 18)
    m.top.findNode("toastText").font = fontFile("medium", 18)

    m.page = m.top.findNode("page")
    m.rail = m.top.findNode("rail")
    m.dockItemsGroup = m.top.findNode("dockItems")
    m.dockLabel = m.top.findNode("dockLabel")
    m.appLayer = m.top.findNode("appLayer")
    m.focusAnim = m.top.findNode("focusAnim")
    m.growInterp = m.top.findNode("growInterp")
    m.shrinkInterp = m.top.findNode("shrinkInterp")
    m.ringIn = m.top.findNode("ringIn")
    m.ringOut = m.top.findNode("ringOut")
    m.pressAnim = m.top.findNode("pressAnim")
    m.pressInterp = m.top.findNode("pressInterp")
    m.pressAnim.observeField("state", "onPressDone")
    m.scrollAnim = m.top.findNode("scrollAnim")
    m.scrollInterp = m.top.findNode("scrollInterp")
    m.pageAnim = m.top.findNode("pageAnim")
    m.pageInterp = m.top.findNode("pageInterp")
    m.heroInterp = m.top.findNode("heroInterp")
    m.veilInterp = m.top.findNode("veilInterp")
    m.captionAnim = m.top.findNode("captionAnim")
    m.captionInterp = m.top.findNode("captionInterp")
    m.wallAnim = m.top.findNode("wallAnim")
    m.wallAnim.observeField("state", "onWallDone")
    springFade(m.wallAnim, m.top.findNode("wallFade"), 0.0, 1.0, "gentle")
    qa = m.top.findNode("quoteAnim")
    springFade(qa, m.top.findNode("quoteFade"), 0.0, 1.0, "gentle")
    springTo(invalid, m.top.findNode("quoteMove"), [68, 442], [68, 428], "gentle")
    na = m.top.findNode("noteAnim")
    springFade(na, m.top.findNode("noteFade"), 0.0, 1.0, "bouncy")
    springTo(invalid, m.top.findNode("noteMove"), [880, 60], [880, 96], "bouncy")
    oa = m.top.findNode("openAnim")
    springFade(oa, m.top.findNode("openPage"), 1.0, 0.0, "smooth")
    springFade(invalid, m.top.findNode("openLayer"), 0.0, 1.0, "smooth")
    springFade(m.top.findNode("closeAnim"), m.top.findNode("closePage"), 0.0, 1.0, "smooth")
    ti = m.top.findNode("toastIn")
    springFade(ti, m.top.findNode("toastInFade"), 0.0, 1.0, "smooth")
    springTo(invalid, m.top.findNode("toastInMove"), [640, 8], [640, 28], "bouncy")
    springFade(m.top.findNode("toastOut"), m.top.findNode("toastOutFade"), 1.0, 0.0, "gentle")
    m.toastTimer = m.top.findNode("toastTimer")
    m.toastTimer.observeField("fire", "onToastTimer")
    ia = m.top.findNode("introAnim")
    springFade(ia, m.top.findNode("introFade"), 0.0, 1.0, "gentle")
    springTo(invalid, m.top.findNode("introHero"), [0, -16], [0, 0], "gentle")
    springTo(invalid, m.top.findNode("introDock"), [0, 640], [0, 540], "smooth")
    driftLoop(m.top.findNode("breathAnim"), m.top.findNode("breathInterp"), [1.0, 1.0], [1.05, 1.05], 48.0)
    m.top.findNode("wallB").observeField("loadStatus", "onWallLoaded")
    m.top.findNode("noteImg").observeField("loadStatus", "onNoteLoaded")

    clock = m.top.findNode("clockTimer")
    clock.observeField("fire", "onClock")
    clock.control = "start"
    st = m.top.findNode("stateTimer")
    st.observeField("fire", "loadState")
    st.control = "start"
    idle = m.top.findNode("idleTimer")
    idle.observeField("fire", "onIdleTick")
    idle.control = "start"
    qt = m.top.findNode("quoteTimer")
    qt.observeField("fire", "nextQuote")
    qt.control = "start"

    m.events = CreateObject("roSGNode", "EventTask")
    m.events.observeField("event", "onServerEvent")
    m.events.observeField("connected", "onEventsConnected")
    m.events.server = m.server
    m.events.control = "RUN"

    m.top.observeField("launchParams", "onLaunchParams")

    onClock()
    renderHeader()
    buildAll()
    loadState()
    loadChannels()
    m.top.setFocus(true)
    m.top.findNode("introAnim").control = "start"
    m.top.findNode("breathAnim").control = "start"
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
        m.serverOk = false
        renderHeader()
        return
    end if
    m.serverOk = true
    m.hub = data.hub
    m.state = data
    m.sleepLeft = data.sleep
    m.sleepStamp = UpTime(0)
    setWallpaper(absUrl(m.server, data.wallpaper))
    sortChannels()
    onClock()
    renderHeader()
    buildAll()
    if m.channels.count() = 0 then loadChannels()
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
    m.channelError = data = invalid
    if data <> invalid and data.apps <> invalid
        for each a in data.apps
            m.channels.push({ id: a.id, name: a.name })
            lname = LCase(a.name)
            if lname = "youtube" then m.ytAppId = a.id
            if Instr(1, lname, "crunchyroll") > 0 then m.crAppId = a.id
        end for
    end if
    sortChannels()
    buildAll()
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

function channelIcon(id as String) as String
    return serverUrl(m.server) + "/api/tv/icon/" + urlEncode(id, false)
end function

function widgetOn(id as String) as Boolean
    list = m.hub.widgets
    if list = invalid or list.count() = 0 then return true
    for each w in list
        if w.id = id then return w.on = true
    end for
    return false
end function

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
    if b.loadStatus = "ready" and b.uri = m.wallUrl then m.wallAnim.control = "start"
end sub

sub onWallDone()
    if m.wallAnim.state <> "stopped" then return
    b = m.top.findNode("wallB")
    if b.opacity < 0.99 then return
    m.top.findNode("wallA").uri = b.uri
    b.opacity = 0
end sub

' =====================================================================  header

sub onClock()
    dt = CreateObject("roDateTime")
    dt.toLocalTime()
    clock24 = m.hub.clock24 = true
    full = hhmm(dt, clock24)
    clockLbl = m.top.findNode("clock")
    ampm = m.top.findNode("ampm")
    if clock24
        clockLbl.text = full
        ampm.text = ""
    else
        clockLbl.text = Left(full, full.len() - 3)
        ampm.text = Right(full, 2)
        w = clockLbl.boundingRect().width
        ampm.translation = [60 + w + 10, 52]
    end if
    months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
    m.top.findNode("date").text = dt.getWeekday() + ", " + months[dt.getMonth() - 1] + " " + dt.getDayOfMonth().toStr()
    if dt.getSeconds() = 0 then renderHeader()
end sub

sub renderHeader()
    ' weather + tip
    w = invalid
    if m.state.widgets <> invalid then w = m.state.widgets.weather
    wGroup = m.top.findNode("weather")
    tip = m.top.findNode("tip")
    if w <> invalid and widgetOn("weather")
        wGroup.visible = true
        m.top.findNode("wIcon").uri = "pkg:/images/w_" + w.icon + ".png"
        line = w.temp.toStr() + Chr(176) + "  " + w.text
        if w.days <> invalid and w.days.count() > 0 then line = line + "     " + w.days[0].hi.toStr() + Chr(176) + " / " + w.days[0].lo.toStr() + Chr(176)
        m.top.findNode("wText").text = line
        if w.tip <> invalid and w.tip <> ""
            tipText = m.top.findNode("tipText")
            tipText.text = w.tip
            m.top.findNode("tipBg").width = tipText.boundingRect().width + 32
            tip.visible = true
        else
            tip.visible = false
        end if
    else
        wGroup.visible = false
        tip.visible = false
    end if
    renderNote()
    renderQuote(false)
    renderChips()
end sub

sub renderNote()
    note = m.top.findNode("note")
    notes = invalid
    if m.state.widgets <> invalid then notes = m.state.widgets.notes
    if notes = invalid or notes.count() = 0 or not widgetOn("note")
        note.visible = false
        m.noteId = ""
        return
    end if
    n = notes[0]
    note.visible = true
    m.top.findNode("noteCaption").text = "Sent " + agoText(n.created)
    isNew = n.id <> m.noteId
    m.noteId = n.id
    img = m.top.findNode("noteImg")
    txt = m.top.findNode("noteText")
    bg = m.top.findNode("noteBg")
    kind = "text"
    if n.kind <> invalid then kind = n.kind
    if kind = "drawing"
        txt.visible = false
        m.top.findNode("noteMask").visible = true
        bg.blendColor = "0x1E1A16E6"
        if isNew then img.uri = absUrl(m.server, n.image)
        if not isNew then note.opacity = 1
    else
        m.top.findNode("noteMask").visible = false
        txt.visible = true
        txt.text = n.text
        txt.color = "0x1E1A16FF"
        bg.blendColor = rokuColor(n.color, "F2")
        if isNew
            m.top.findNode("noteAnim").control = "start"
        else
            note.opacity = 1
        end if
    end if
end sub

sub onNoteLoaded()
    if m.top.findNode("noteImg").loadStatus = "ready" then m.top.findNode("noteAnim").control = "start"
end sub

sub nextQuote()
    renderQuote(true)
end sub

' The quote over the dock: one of your saved quotes, or a greeting if there are none.
sub renderQuote(advance as Boolean)
    q = m.top.findNode("quote")
    t = m.top.findNode("quoteText")
    by = m.top.findNode("quoteBy")
    quotes = m.state.quotes
    if not widgetOn("quote")
        q.visible = false
        return
    end if
    q.visible = true
    old = t.text
    if quotes = invalid or quotes.count() = 0
        dt = CreateObject("roDateTime")
        dt.toLocalTime()
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
        t.text = greeting
        by.text = ""
    else
        if advance or m.quoteIndex < 0 or m.quoteIndex >= quotes.count()
            n = quotes.count()
            nxt = Rnd(n) - 1
            if n > 1 and nxt = m.quoteIndex then nxt = (nxt + 1) mod n
            m.quoteIndex = nxt
        end if
        item = quotes[m.quoteIndex]
        t.text = Chr(8220) + item.text + Chr(8221)
        by.text = ""
        if item.by <> invalid and item.by <> "" then by.text = item.by
    end if
    lines = 1
    if t.boundingRect().height > 60 then lines = 2
    if lines = 2
        t.translation = [0, -44]
    else
        t.translation = [0, 0]
    end if
    by.translation = [2, 50]
    if t.text <> old then m.top.findNode("quoteAnim").control = "start"
end sub

' Pills at the top right: sleep timer, server problems.
sub renderChips()
    chips = m.top.findNode("chips")
    chips.removeChildrenIndex(chips.getChildCount(), 0)
    items = []
    if m.sleepLeft <> invalid
        secsLeft = m.sleepLeft - (UpTime(0) - m.sleepStamp)
        if secsLeft > 0 then items.push({ icon: "pkg:/images/moon.png", text: "Off " + untilText(secsLeft) })
    end if
    if m.server = ""
        items.push({ dot: pal("dim"), text: "Set up the Hub server in Settings" })
    else if not m.serverOk
        items.push({ dot: pal("coral"), text: "Hub server offline" })
    end if
    x = 0
    for i = items.count() - 1 to 0 step -1
        it = items[i]
        g = CreateObject("roSGNode", "Group")
        lbl = CreateObject("roSGNode", "Label")
        lbl.font = fontFile("regular", 17)
        lbl.color = pal("cream")
        lbl.text = it.text
        lbl.height = 40
        lbl.vertAlign = "center"
        tw = lbl.boundingRect().width
        padL = 34
        if it.icon <> invalid then padL = 46
        pw = tw + padL + 18
        x = x - pw
        bg = g.createChild("Poster")
        bg.uri = "pkg:/images/pill.9.png"
        bg.width = pw
        bg.height = 40
        bg.blendColor = "0x1E1A16E0"
        if it.icon <> invalid
            ic = g.createChild("Poster")
            ic.uri = it.icon
            ic.width = 24
            ic.height = 24
            ic.translation = [14, 8]
        else
            dot = g.createChild("Poster")
            dot.uri = "pkg:/images/dot.png"
            dot.width = 10
            dot.height = 10
            dot.translation = [16, 15]
            dot.blendColor = it.dot
        end if
        lbl.translation = [padL, 0]
        g.appendChild(lbl)
        g.translation = [x, 0]
        chips.appendChild(g)
        x = x - 10
    end for
    ' keep the note clear of the chips
    noteY = 96
    if items.count() > 0 then noteY = 100
    m.top.findNode("note").translation = [880, noteY]
end sub

' =====================================================================  dock + rows

' A focus target "row": the dock is row 0, the lower deck rows follow.
function newRow(id as String, title as String, cardH as Integer, scale as Float) as Object
    g = CreateObject("roSGNode", "Group")
    g.id = "row_" + id
    t = g.createChild("Label")
    t.text = title
    t.font = m.fRow
    t.color = "0xF2EADCE6"
    t.translation = [80, -4]
    clip = g.createChild("Group")
    clip.translation = [80, 46]
    clip.clippingRect = [-80, -70, 1280, cardH + 140]
    cards = clip.createChild("Group")
    cards.id = "cards_" + id
    cap = g.createChild("Label")
    cap.id = "cap_" + id
    cap.font = m.fCaption
    cap.color = pal("cream")
    cap.width = 1100
    cap.translation = [80, 46 + cardH + 18]
    cap.opacity = 0
    return { id: id, group: g, cardsGroup: cards, caption: cap, cards: [], xs: [], ws: [], captions: [], data: [], col: 0, offset: 0, cardH: cardH, height: 46 + cardH + 80, scale: scale, visible: 1120, gap: 26, y: 0 }
end function

sub addCard(row as Object, card as Object, w as Integer, caption as String, item as Dynamic)
    x = 0
    n = row.cards.count()
    if n > 0 then x = row.xs[n - 1] + row.ws[n - 1] + row.gap
    card.translation = [x, 0]
    row.cardsGroup.appendChild(card)
    row.cards.push(card)
    row.xs.push(x)
    row.ws.push(w)
    row.captions.push(caption)
    row.data.push(item)
end sub

' card shell: 0 focus ring, 1 face, then content
function makeCard(row as Object, w as Integer, h as Integer, faceColor as String) as Object
    g = CreateObject("roSGNode", "Group")
    g.id = "c_" + row.id + "_" + row.cards.count().toStr()
    g.scaleRotateCenter = [w / 2, h / 2]
    ring = g.createChild("Poster")
    ring.id = g.id + "_ring"
    ring.uri = "pkg:/images/ring.9.png"
    ring.width = w + 14
    ring.height = h + 14
    ring.translation = [-7, -7]
    ring.blendColor = pal("cream")
    ring.opacity = 0
    face = g.createChild("Poster")
    face.uri = "pkg:/images/card.9.png"
    face.width = w
    face.height = h
    face.blendColor = faceColor
    m.cardScale[g.id] = row.scale
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

function addArt(card as Object, uri as String, maskUri as String, w as Integer, h as Integer, shade as Boolean) as Object
    mask = card.createChild("MaskGroup")
    mask.maskUri = maskUri
    mask.maskSize = [w, h]
    art = mask.createChild("Poster")
    art.uri = uri
    art.width = w
    art.height = h
    art.loadWidth = w
    art.loadHeight = h
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

sub buildAll()
    keepId = ""
    keepCol = 0
    if m.rows.count() > 0 and m.rowIndex < m.rows.count()
        keepId = m.rows[m.rowIndex].id
        keepCol = m.rows[m.rowIndex].col
    end if
    m.focusAnim.control = "stop"
    m.scrollAnim.control = "stop"
    m.focusedCard = invalid
    m.shrinking = invalid
    m.cardScale = {}
    m.rows = [buildDock()]

    m.rail.removeChildrenIndex(m.rail.getChildCount(), 0)
    y = 0
    order = ["myshows", "youtube", "anime", "agenda"]
    if m.hub.rows <> invalid and m.hub.rows.count() > 0
        order = []
        for each r in m.hub.rows
            if r.on = true then order.push(r.id)
        end for
    end if
    for each id in order
        row = invalid
        if id = "myshows" then row = showsRow()
        if id = "youtube" then row = youtubeRow()
        if id = "anime" then row = animeRow()
        if id = "agenda" then row = agendaRow()
        if row <> invalid
            row.y = y
            row.group.translation = [0, y]
            m.rail.appendChild(row.group)
            m.rows.push(row)
            y = y + row.height
        end if
    end for

    m.rowIndex = 0
    for i = 0 to m.rows.count() - 1
        if m.rows[i].id = keepId then m.rowIndex = i
    end for
    row = m.rows[m.rowIndex]
    col = keepCol
    if col > row.cards.count() - 1 then col = row.cards.count() - 1
    if col < 0 then col = 0
    focusCard(m.rowIndex, col, false)
end sub

' -- the dock: channels, then Hub's tools
function buildDock() as Object
    m.dockItemsGroup.removeChildrenIndex(m.dockItemsGroup.getChildCount(), 0)
    row = { id: "dock", cardsGroup: m.dockItemsGroup, caption: m.dockLabel, cards: [], xs: [], ws: [], captions: [], data: [], col: 0, offset: 0, cardH: m.DOCK_H, height: 0, scale: 1.18, visible: 1110, gap: m.DOCK_GAP, y: 0 }
    for each c in m.channels
        card = makeCard(row, m.DOCK_W, m.DOCK_H, "0xF2EADC1A")
        addArt(card, channelIcon(c.id), "pkg:/images/mask_dock.png", m.DOCK_W, m.DOCK_H, false)
        addCard(row, card, m.DOCK_W, c.name, { kind: "channel", id: c.id, name: c.name })
    end for
    for each t in m.tools
        card = makeCard(row, m.DOCK_W, m.DOCK_H, pal("raise"))
        ic = addImage(card, t.icon, (m.DOCK_W - 44) / 2, (m.DOCK_H - 44) / 2, 44, 44)
        ic.blendColor = pal("cream")
        if row.cards.count() > 0 and t.id = "pick" and m.channels.count() > 0
            ' a little extra space between channels and tools
            row.gap = 40
        end if
        addCard(row, card, m.DOCK_W, t.name, { kind: "tool", id: t.id, name: t.name })
        row.gap = m.DOCK_GAP
    end for

    total = row.xs[row.xs.count() - 1] + m.DOCK_W
    bg = m.top.findNode("dockBg")
    if total <= row.visible
        x0 = Int((1280 - total) / 2)
        bg.width = total + 48
        bg.translation = [x0 - 24, 0]
        row.visible = total
    else
        x0 = 85
        bg.width = row.visible + 48
        bg.translation = [x0 - 24, 0]
    end if
    row.x0 = x0
    m.top.findNode("dockClip").clippingRect = [x0 - 30, -60, row.visible + 60, 220]
    m.dockItemsGroup.translation = [x0, 0]
    return row
end function

' -- Hub's pick for you first, then your saved shows
function showsRow() as Object
    shows = m.state.shows
    if shows = invalid then shows = []
    pick = m.state.pick
    if shows.count() = 0 and pick = invalid then return invalid
    title = "My Shows"
    if shows.count() = 0 then title = "For you"
    row = newRow("myshows", title, 225, 1.08)
    card = makeCard(row, 360, 225, pal("panel"))
    if pick <> invalid
        addArt(card, thumbUrl(m.server, pick.poster), "pkg:/images/mask_poster.png", 150, 225, false)
        k = addLabel(card, "Hub recommends", 170, 22, 176, m.fSmallBold, rokuColor(m.hub.accent, "FF"))
        t = addLabel(card, pick.title, 170, 50, 176, fontFile("title", 24), pal("cream"))
        t.wrap = true
        t.maxLines = 3
        r = addLabel(card, pick.reason, 170, 150, 176, m.fSmall, "0xF2EADCB0")
        r.wrap = true
        r.maxLines = 3
        caption = "Hub recommends " + pick.title + ". OK for more picks"
    else
        ic = addImage(card, "pkg:/images/i_pick.png", 148, 46, 64, 64)
        ic.blendColor = pal("cream")
        l1 = addLabel(card, "What should I watch?", 0, 130, 360, fontFile("title", 24), pal("cream"))
        l1.horizAlign = "center"
        l2 = addLabel(card, "Hub recommends a show or movie", 0, 166, 360, m.fSmall, "0xF2EADCA8")
        l2.horizAlign = "center"
        caption = "Get a recommendation"
    end if
    addCard(row, card, 360, caption, { kind: "recommend" })
    for each s in shows
        card = makeCard(row, 150, 225, "0xF2EADC14")
        addArt(card, thumbUrl(m.server, s.poster), "pkg:/images/mask_poster.png", 150, 225, true)
        if s.appName <> invalid and s.appName <> "" then addLabel(card, s.appName, 10, 196, 130, m.fSmallBold, "0xF2EADCE6")
        caption = s.title
        if s.appName <> invalid and s.appName <> "" then caption = s.title + "   on " + s.appName
        addCard(row, card, 150, caption, { kind: "show", show: s })
    end for
    return row
end function

function youtubeRow() as Object
    vids = invalid
    if m.state.widgets <> invalid then vids = m.state.widgets.youtube
    if vids = invalid or vids.count() = 0 then return invalid
    row = newRow("youtube", "New on YouTube", 180, 1.08)
    n = vids.count()
    if n > 14 then n = 14
    for i = 0 to n - 1
        v = vids[i]
        card = makeCard(row, 320, 180, "0xF2EADC14")
        addArt(card, thumbUrl(m.server, v.thumb), "pkg:/images/mask_thumb.png", 320, 180, true)
        t = addLabel(card, v.title, 16, 104, 288, m.fSmallBold, pal("cream"))
        t.wrap = true
        t.maxLines = 2
        addLabel(card, v.channel, 16, 150, 288, m.fSmall, "0xF2EADCB0")
        addCard(row, card, 320, v.title, { kind: "video", video: v })
    end for
    return row
end function

function animeRow() as Object
    list = invalid
    if m.state.widgets <> invalid then list = m.state.widgets.anime
    if list = invalid or list.count() = 0 then return invalid
    row = newRow("anime", "Anime episodes", 212, 1.08)
    now = CreateObject("roDateTime").asSeconds()
    for each a in list
        card = makeCard(row, 150, 212, "0xF2EADC14")
        addArt(card, thumbUrl(m.server, a.cover), "pkg:/images/mask_cover.png", 150, 212, true)
        sub1 = ""
        sub2 = ""
        if a.episode <> invalid
            sub1 = "Ep " + a.episode.toStr()
            sub2 = untilText(a.airingAt - now)
        else if a.status <> invalid
            sub1 = LCase(a.status)
        end if
        addLabel(card, sub1, 12, 158, 128, m.fSmallBold, pal("cream"))
        addLabel(card, sub2, 12, 182, 128, m.fSmall, "0xF2EADCC0")
        caption = a.title
        if a.airingAt <> invalid then caption = a.title + "   " + dayAndTime(a.airingAt, m.hub.clock24 = true, false)
        addCard(row, card, 150, caption, { kind: "anime" })
    end for
    return row
end function

function agendaRow() as Object
    list = invalid
    if m.state.widgets <> invalid then list = m.state.widgets.agenda
    if list = invalid or list.count() = 0 then return invalid
    row = newRow("agenda", "Up next", 130, 1.06)
    n = list.count()
    if n > 8 then n = 8
    clock24 = m.hub.clock24 = true
    for i = 0 to n - 1
        e = list[i]
        card = makeCard(row, 300, 130, pal("panel"))
        addLabel(card, dayAndTime(e.start, clock24, e.allDay = true), 22, 22, 256, m.fSmallBold, rokuColor(m.hub.accent, "FF"))
        t = addLabel(card, e.title, 22, 50, 256, m.fTitle, pal("cream"))
        t.wrap = true
        t.maxLines = 2
        addCard(row, card, 300, "", { kind: "event" })
    end for
    return row
end function

' =====================================================================  focus

sub focusCard(rowIdx as Integer, col as Integer, animate as Boolean)
    row = m.rows[rowIdx]
    if col < 0 or col >= row.cards.count() then return
    card = row.cards[col]
    s = row.scale

    m.focusAnim.control = "stop"
    if m.shrinking <> invalid and not m.shrinking.isSameNode(card)
        m.shrinking.scale = [1.0, 1.0]
        m.shrinking.getChild(0).opacity = 0
    end if
    m.shrinking = invalid
    m.shrinkInterp.fieldToInterp = "dummy.scale"
    m.ringOut.fieldToInterp = "dummy.opacity"
    if m.focusedCard <> invalid and not m.focusedCard.isSameNode(card)
        ' the old card springs back from wherever it is right now
        m.shrinking = m.focusedCard
        springTo(invalid, m.shrinkInterp, m.focusedCard.scale, [1.0, 1.0], "snappy")
        springFade(invalid, m.ringOut, m.focusedCard.getChild(0).opacity, 0.0, "snappy")
        m.shrinkInterp.fieldToInterp = m.focusedCard.id + ".scale"
        m.ringOut.fieldToInterp = m.focusedCard.id + "_ring.opacity"
    end if
    if animate
        springTo(m.focusAnim, m.growInterp, card.scale, [s, s], "snappy")
        springFade(invalid, m.ringIn, card.getChild(0).opacity, 1.0, "snappy")
        m.growInterp.fieldToInterp = card.id + ".scale"
        m.ringIn.fieldToInterp = card.id + "_ring.opacity"
        m.focusAnim.control = "start"
    else
        if m.shrinking <> invalid
            m.shrinking.scale = [1.0, 1.0]
            m.shrinking.getChild(0).opacity = 0
            m.shrinking = invalid
        end if
        card.scale = [s, s]
        card.getChild(0).opacity = 1
    end if

    rowChanged = rowIdx <> m.rowIndex
    if rowChanged and m.rowIndex < m.rows.count() then m.rows[m.rowIndex].caption.opacity = 0
    row.caption.text = row.captions[col]
    if row.captions[col] <> ""
        m.captionAnim.control = "stop"
        springFade(m.captionAnim, m.captionInterp, 0.0, 1.0, "gentle")
        m.captionInterp.fieldToInterp = row.caption.id + ".opacity"
        m.captionAnim.control = "start"
    else
        row.caption.opacity = 0
    end if

    m.focusedCard = card
    m.rowIndex = rowIdx
    row.col = col
    scrollRowTo(row, col, animate)
    if row.id = "dock" then row.caption.translation = [row.x0 + row.xs[col] + row.offset + m.DOCK_W / 2 - 120, 140]
    scrollPage(rowIdx, animate)
end sub

sub scrollRowTo(row as Object, col as Integer, animate as Boolean)
    x = row.xs[col]
    w = row.ws[col]
    target = row.offset
    if x + target > row.visible - w then target = (row.visible - w) - x
    if x + target < 0 then target = -x
    if target = row.offset then return
    row.offset = target
    base = 0
    if row.id = "dock" then base = row.x0
    if animate
        m.scrollAnim.control = "stop"
        m.scrollInterp.fieldToInterp = row.cardsGroup.id + ".translation"
        springTo(m.scrollAnim, m.scrollInterp, row.cardsGroup.translation, [base + target, 0], "smooth")
        m.scrollAnim.control = "start"
    else
        row.cardsGroup.translation = [base + target, 0]
    end if
end sub

' Dock focused: the photo and header show. Lower deck: the page slides up over a dark veil.
sub scrollPage(rowIdx as Integer, animate as Boolean)
    if rowIdx = 0
        target = [0, 0]
        hero = 1.0
        veil = 0.0
    else
        target = [0, 150 - (m.RAIL_TOP + m.rows[rowIdx].y)]
        hero = 0.0
        veil = 1.0
    end if
    if animate
        m.pageAnim.control = "stop"
        springTo(m.pageAnim, m.pageInterp, m.page.translation, target, "smooth")
        springFade(invalid, m.heroInterp, m.top.findNode("hero").opacity, hero, "smooth")
        springFade(invalid, m.veilInterp, m.top.findNode("deckVeil").opacity, veil, "smooth")
        m.pageAnim.control = "start"
    else
        m.page.translation = target
        m.top.findNode("hero").opacity = hero
        m.top.findNode("deckVeil").opacity = veil
    end if
end sub

sub moveRow(delta as Integer)
    n = m.rowIndex + delta
    if n < 0 or n >= m.rows.count() then return
    cur = m.rows[m.rowIndex]
    target = m.rows[n]
    curBase = 80
    if cur.id = "dock" then curBase = cur.x0
    tBase = 80
    if target.id = "dock" then tBase = target.x0
    x = curBase + cur.xs[cur.col] + cur.offset + cur.ws[cur.col] / 2
    best = target.col
    bestD = 99999
    for i = 0 to target.cards.count() - 1
        d = Abs(tBase + target.xs[i] + target.offset + target.ws[i] / 2 - x)
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
    s = m.cardScale[m.focusedCard.id]
    if s = invalid then s = 1.08
    springPress(m.pressAnim, m.pressInterp, s)
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
    item = row.data[row.col]
    if item = invalid then return
    if item.kind = "channel"
        launchApp(item.id, invalid, "Opening " + item.name)
    else if item.kind = "tool"
        openTool(item.id)
    else if item.kind = "recommend"
        openTool("pick")
    else if item.kind = "show"
        playShow(item.show)
    else if item.kind = "video"
        launchApp(m.ytAppId, { "contentId": item.video.id, "mediaType": "movie" }, "Playing on YouTube")
    else if item.kind = "anime"
        launchApp(m.crAppId, invalid, "Opening Crunchyroll")
    end if
end sub

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
    else if id = "library"
        openApp("LibraryApp", {})
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

sub playShow(show as Object)
    msg = "Finding " + show.title
    if show.appName <> invalid and show.appName <> "" then msg = msg + " on " + show.appName
    showToast(msg)
    startRequest(serverUrl(m.server) + "/api/shows/play", "POST", FormatJson({ "id": show.id }), "onLaunched")
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
    m.page.opacity = 1
    m.idleSecs = 0
    onClock()
    renderQuote(true)
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
        ' a question asked from the phone: show it with its answer
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
    m.top.findNode("toastOut").control = "stop"
    a = m.top.findNode("toastIn")
    a.control = "stop"
    springFade(a, m.top.findNode("toastInFade"), m.top.findNode("toast").opacity, 1.0, "smooth")
    a.control = "start"
    m.toastTimer.control = "stop"
    m.toastTimer.control = "start"
end sub

sub onToastTimer()
    a = m.top.findNode("toastOut")
    springFade(a, m.top.findNode("toastOutFade"), m.top.findNode("toast").opacity, 0.0, "gentle")
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
        weather = invalid
        if m.state.widgets <> invalid then weather = m.state.widgets.weather
        openApp("AmbientApp", { weather: weather, clock24: m.hub.clock24 = true })
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
        dlg.message = ["No Hub server set yet.", "Enter your CasaOS server's address to turn on your channels, the browser, widgets, notes and the remote app."]
    else
        dlg.message = [
            "Hub server: " + m.server,
            "Change wallpapers, quotes, shows and widgets from the Hub Remote app or " + serverUrl(m.server) + "."
        ]
    end if
    dlg.buttons = ["Set server address", "Reload everything", "Privacy and terms", "Remove Hub from this TV", "Close"]
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
    dlg.message = ["Type your TV's developer password. Hub closes and disappears from the home screen. To remove the Hub server and its data too, use Settings > Remove Hub on the control page."]
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
    if kind = "server" or kind = "remove" then text = m.dialog.text.trim()
    closeDialog()
    if kind = "settings"
        if idx = 0
            askServer()
        else if idx = 1
            loadChannels()
            loadState()
        else if idx = 2
            showLegal()
        else if idx = 3
            askRemove()
        end if
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
        renderHeader()
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
        if m.rowIndex > 0 then focusCard(0, m.rows[0].col, true)
    else if key = "play"
        openTool("pick")
    else
        return false
    end if
    return true
end function
