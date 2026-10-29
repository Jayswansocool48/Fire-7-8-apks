' The PlayStation (PS4) look, a skin over the same rows and actions as Hub.
'   Up top: Media / Apps / Search, then a row of square tiles. The focused tile grows, shows
'   its name, and its artwork fills the screen.
'   Below: the focused title's details, where it streams on your TV, and buttons.
'   Down again: Today cards (weather, last watched, next event, note, sleep at night).

sub init()
    m.CATS = ["Media", "Apps", "Search"]
    m.cat = 0
    m.z = 1
    m.col = 0
    m.offset = 0
    m.detail = {}
    m.pending = {}
    m.bgUrl = ""
    m.bgFront = "bgA"
    m.tiles = []
    m.items = []
    m.buttons = []
    m.bcol = 0
    m.cards = []
    m.cardItems = []
    m.ccol = 0
    m.catCol = 0
    m.searchSeq = -1
    m.SMALL = 100
    m.BIG = 150
    m.GAP = 16

    m.fKicker = fontFile("semibold", 18)
    m.fMeta = fontFile("medium", 20)
    m.fBody = fontFile("regular", 19)
    m.fSmall = fontFile("regular", 16)
    m.fSmallBold = fontFile("semibold", 16)
    m.fCard = fontFile("semibold", 18)
    m.fButton = fontFile("semibold", 19)
    m.fChip = fontFile("medium", 16)
    m.fCat = fontFile("semibold", 22)
    m.top.findNode("kicker").font = m.fKicker
    m.top.findNode("title").font = fontFile("title", 58)
    m.top.findNode("meta").font = m.fMeta
    m.top.findNode("summary").font = m.fBody
    m.top.findNode("tileName").font = fontFile("title", 26)
    m.top.findNode("cardsTitle").font = fontFile("title", 24)
    m.top.findNode("clockLbl").font = fontFile("semibold", 22)
    m.content = m.top.findNode("content")
    m.strip = m.top.findNode("strip")
    m.top.findNode("bgA").observeField("loadStatus", "onBgLoaded")
    m.top.findNode("bgB").observeField("loadStatus", "onBgLoaded")
    m.top.findNode("settleTimer").observeField("fire", "onSettle")
    m.top.observeField("focusedChild", "onFocusChange")
end sub

sub onFocusChange()
    if m.top.hasFocus() then paintAll(false)
end sub

sub onClockText()
    m.top.findNode("clockLbl").text = m.top.clock
end sub

sub onData()
    d = m.top.data
    if d = invalid then return
    m.d = d
    m.server = d.server
    m.hub = d.hub
    m.st = d.state
    if m.st = invalid then m.st = {}
    if d.search <> invalid and d.searchSeq <> m.searchSeq
        m.searchSeq = d.searchSeq
        m.cat = 2
        m.col = 0
        m.z = 1
    end if
    buildCorner()
    buildCats()
    buildStrip()
    buildCards()
    paintAll(false)
end sub

' ---------------------------------------------------------------- building

sub buildCorner()
    av = m.top.findNode("avatar")
    av.removeChildrenIndex(av.getChildCount(), 0)
    prof = invalid
    for each p in listOf(m.st.profiles)
        if isTrue(p.current) then prof = p
    end for
    name = "Profile"
    color = m.hub.accent
    if prof <> invalid
        name = prof.name
        color = prof.color
    end if
    ring = uiPatch(av, "pkg:/images/circle.png", -4, -4, 48, 48, pal("cream"))
    ring.id = "avRing"
    ring.opacity = 0
    uiAvatar(av, name, color, 0, 0, 40, fontFile("semibold", 18))
    av.translation = [-40, 0]
    m.top.findNode("clockLbl").text = m.top.clock
end sub

sub buildCats()
    g = m.top.findNode("cats")
    g.removeChildrenIndex(g.getChildCount(), 0)
    m.catNodes = []
    x = 0
    names = []
    for each n in m.CATS
        names.push(n)
    end for
    names.push("Profile")
    for i = 0 to m.CATS.count() - 1
        c = g.createChild("Group")
        c.translation = [x, 0]
        bg = uiPatch(c, "pkg:/images/pill.9.png", -14, -4, 10, 40, pal("cream"))
        bg.opacity = 0
        l = uiLabel(c, m.CATS[i], 0, 0, 0, m.fCat, pal("mist"))
        l.height = 32
        l.vertAlign = "center"
        w = l.boundingRect().width
        bg.width = w + 28
        m.catNodes.push(c)
        x = x + w + 40
    end for
end sub

function mediaItems() as Object
    out = []
    seen = {}
    lists = [{ k: "Keep watching", l: listOf(m.st.history) }, { k: "My Shows", l: listOf(m.st.shows) }, { k: "Hub recommends", l: listOf(m.st.foryou) }]
    for each part in lists
        for each it in part.l
            dk = detailKey(it)
            if not seen.doesExist(dk) and out.count() < 24
                it.kicker = part.k
                out.push(it)
                seen[dk] = true
            end if
        end for
    end for
    return out
end function

sub buildStrip()
    m.strip.removeChildrenIndex(m.strip.getChildCount(), 0)
    if m.cat = 0
        m.items = mediaItems()
    else if m.cat = 1
        m.items = appItems(0, true)
    else
        m.items = listOf(m.d.search)
        for each it in m.items
            it.kicker = "Search result"
        end for
    end if
    if m.items.count() = 0
        m.items = [{ kind: "hint", title: "Nothing here yet", text: "Save shows from Hub Remote and they show up here." }]
    end if
    m.tiles = []
    for i = 0 to m.items.count() - 1
        it = m.items[i]
        if it.kind = invalid then it.kind = "hint"
        t = m.strip.createChild("Group")
        t.id = "st" + i.toStr()
        t.scaleRotateCenter = [0, 0]
        ring = uiPatch(t, "pkg:/images/ring.9.png", -7, -7, m.SMALL + 14, m.SMALL + 14, pal("cream"))
        ring.opacity = 0
        face = uiPatch(t, "pkg:/images/card.9.png", 0, 0, m.SMALL, m.SMALL, pal("raise"))
        if it.kind = "channel"
            mk = t.createChild("MaskGroup")
            mk.maskUri = "pkg:/images/mask_square.png"
            mk.maskSize = [m.SMALL, m.SMALL]
            p = mk.createChild("Poster")
            p.uri = serverUrl(m.server) + "/api/tv/icon/" + urlEncode(it.id, false)
            p.width = m.SMALL
            p.height = m.SMALL
            p.loadDisplayMode = "scaleToFill"
        else if it.kind = "tool"
            ic = uiImage(t, it.icon, 28, 28, 44, 44)
            ic.blendColor = pal("cream")
        else if isTitle(it)
            art = txt(it.poster)
            if art <> ""
                uiArt(t, thumbUrl(m.server, art), "pkg:/images/mask_square.png", m.SMALL, m.SMALL, false)
            else
                l = uiLabel(t, it.title, 8, 8, m.SMALL - 16, m.fSmallBold, pal("cream"))
                l.wrap = true
                l.maxLines = 4
            end if
        else
            ic = uiImage(t, "pkg:/images/i_info.png", 30, 30, 40, 40)
            ic.blendColor = pal("cream")
        end if
        m.tiles.push(t)
    end for
    if m.col >= m.tiles.count() then m.col = m.tiles.count() - 1
    if m.col < 0 then m.col = 0
    layoutStrip(false)
end sub

' Lays the tiles out with the focused one big and its name beside it.
sub layoutStrip(animate as Boolean)
    nameLbl = m.top.findNode("tileName")
    x = 0
    fx = 0
    for i = 0 to m.tiles.count() - 1
        t = m.tiles[i]
        big = i = m.col
        if animate
            animTo(t, "translation", [x, 0], "smooth")
        else
            t.translation = [x, 0]
        end if
        s = 1.0
        if big then s = m.BIG / m.SMALL
        if animate
            animTo(t, "scale", [s, s], "smooth")
        else
            t.scale = [s, s]
        end if
        ring = t.getChild(0)
        o = 0.0
        if big and m.z = 1 then o = 1.0
        animTo(ring, "opacity", o, "snappy")
        if big
            fx = x
            nameLbl.text = m.items[i].title
            x = x + m.BIG + 22 + nameLbl.boundingRect().width + 34
        else
            x = x + m.SMALL + m.GAP
        end if
    end for
    target = 0
    if fx > 260 then target = -(fx - 260)
    m.offset = target
    if animate
        animTo(m.strip, "translation", [64 + target, 92], "smooth")
    else
        m.strip.translation = [64 + target, 92]
    end if
    nameLbl.translation = [64 + target + fx + m.BIG + 22, 92 + Int(m.BIG / 2) - 18]
    nameLbl.opacity = 0
    animTo(nameLbl, "opacity", 1.0, "gentle")
end sub

sub buildCards()
    g = m.top.findNode("cards")
    g.removeChildrenIndex(g.getChildCount(), 0)
    m.cards = []
    m.cardItems = todayItems()
    x = 0
    for i = 0 to m.cardItems.count() - 1
        it = m.cardItems[i]
        t = uiTile(g, "cc" + i.toStr(), 260, 150, pal("panel"))
        fillWidget(t, it, 260, 150)
        t.translation = [x, 0]
        m.cards.push(t)
        x = x + 282
    end for
    if m.ccol >= m.cards.count() then m.ccol = 0
end sub

' ---------------------------------------------------------------- the details area

sub showInfo(it as Object)
    kick = m.top.findNode("kicker")
    title = m.top.findNode("title")
    meta = m.top.findNode("meta")
    summary = m.top.findNode("summary")
    avail = m.top.findNode("avail")
    avail.removeChildrenIndex(avail.getChildCount(), 0)
    kick.color = rokuColor(m.hub.accent, "FF")
    det = invalid
    if isTitle(it) then det = detailFor(it)
    if det <> invalid and det.title = invalid then det = invalid
    src = it
    if det <> invalid then src = det
    k = txt(it.kicker)
    if it.kind = "channel" then k = "Channel"
    if it.kind = "tool" then k = "Hub"
    kick.text = UCase(k)
    title.text = it.title
    if isTitle(it)
        line = metaLine(src)
        if det <> invalid and det.seasons <> invalid and det.seasons.count() > 1 then line = line + "  " + Chr(183) + "  " + det.seasons.count().toStr() + " seasons"
        meta.text = line
        body = txt(src.summary)
        if body = "" then body = txt(it.reason)
        summary.text = body
    else
        meta.text = itemMeta(it)
        body = itemSummary(it)
        if it.kind = "hint" then body = txt(it.text)
        summary.text = body
    end if
    y = 26 + 74
    meta.translation = [2, y]
    y = y + 36
    aw = 0
    if isTitle(it) then aw = drawAvail(avail, it, det)
    avail.translation = [0, y]
    if aw > 0 then y = y + 48
    summary.translation = [2, y]
    if summary.text <> "" then y = y + summary.boundingRect().height + 22
    bg = m.top.findNode("buttons")
    bg.removeChildrenIndex(bg.getChildCount(), 0)
    bg.translation = [0, y]
    specs = []
    if isTitle(it)
        watch = "Play"
        if det <> invalid and det.watchOn <> invalid
            watch = "Watch on " + det.watchOn.appName
        else if txt(it.appName) <> ""
            watch = "Watch on " + it.appName
        end if
        specs = [{ id: "play", text: watch, icon: "pkg:/images/i_play.png" }, { id: "details", text: "Details", icon: "pkg:/images/i_info.png" }, { id: "more", text: "More picks", icon: "" }]
    else if it.kind = "channel" or it.kind = "tool"
        specs = [{ id: "open", text: "Start", icon: "pkg:/images/i_play.png" }]
    end if
    m.buttons = uiButtons(bg, "cb", specs, m.fButton)
    if m.bcol >= m.buttons.count() then m.bcol = 0
    for i = 0 to m.buttons.count() - 1
        uiButtonFocus(m.buttons[i], m.z = 2 and i = m.bcol)
    end for
    art = txt(src.backdrop)
    if art = "" and isTitle(it) then art = txt(src.poster)
    setBackdrop(art)
end sub

sub detailReady(cur as Object)
    showInfo(cur)
end sub

function focusedItem() as Dynamic
    if m.items.count() = 0 then return invalid
    return m.items[m.col]
end function

sub onSettle()
    it = focusedItem()
    if it <> invalid and isTitle(it) then requestDetail(it)
end sub

' ---------------------------------------------------------------- focus

sub paintAll(animate as Boolean)
    for i = 0 to m.catNodes.count() - 1
        c = m.catNodes[i]
        bg = c.getChild(0)
        l = c.getChild(1)
        if m.z = 0 and i = m.catCol
            animTo(bg, "opacity", 1.0, "snappy")
            l.color = pal("ink")
        else
            animTo(bg, "opacity", 0.0, "snappy")
            if i = m.cat
                l.color = pal("cream")
            else
                l.color = pal("mist")
            end if
        end if
    end for
    ring = m.top.findNode("avRing")
    if ring <> invalid
        o = 0.0
        if m.z = 0 and m.catCol = m.CATS.count() then o = 1.0
        animTo(ring, "opacity", o, "snappy")
    end if
    for i = 0 to m.tiles.count() - 1
        o = 0.0
        if m.z = 1 and i = m.col then o = 1.0
        animTo(m.tiles[i].getChild(0), "opacity", o, "snappy")
    end for
    for i = 0 to m.buttons.count() - 1
        uiButtonFocus(m.buttons[i], m.z = 2 and i = m.bcol)
    end for
    for i = 0 to m.cards.count() - 1
        uiFocus(m.cards[i], m.z = 3 and i = m.ccol, 1.05)
    end for
    up = 0
    if m.z = 3 then up = -330
    if animate
        animTo(m.content, "translation", [0, up], "smooth")
    else
        m.content.translation = [0, up]
    end if
    it = focusedItem()
    if m.z = 3 and m.ccol < m.cardItems.count()
        ' the card's details show while the cards are up
    else if it <> invalid
        showInfo(it)
    end if
end sub

sub moveTile(delta as Integer)
    n = m.col + delta
    if n < 0 or n >= m.tiles.count() then return
    m.col = n
    m.bcol = 0
    layoutStrip(true)
    paintAll(true)
    st = m.top.findNode("settleTimer")
    st.control = "stop"
    st.control = "start"
end sub

sub setCat(idx as Integer)
    if idx = m.cat then return
    m.cat = idx
    m.col = 0
    buildStrip()
    paintAll(true)
end sub

sub activate()
    if m.z = 0
        if m.catCol = m.CATS.count()
            act({ kind: "profile" })
        else if m.catCol = 2
            act({ kind: "search" })
        else
            setCat(m.catCol)
            m.z = 1
            paintAll(true)
        end if
        return
    end if
    if m.z = 3
        it = m.cardItems[m.ccol]
        springPressTile(m.cards[m.ccol], 1.05)
        k = it.kind
        if k = "last"
            act({ kind: "play", item: it.item })
        else if k = "sleep"
            act({ kind: "sleep", minutes: 30 })
        else if k = "music"
            act({ kind: "tool", id: "music" })
        else if k = "tool"
            act({ kind: "tool", id: it.id })
        else if k = "weather"
            act({ kind: "tool", id: "ask" })
        end if
        return
    end if
    it = focusedItem()
    if it = invalid then return
    bid = "open"
    if m.z = 2 and m.bcol < m.buttons.count() then bid = m.buttons[m.bcol].id
    if m.z = 1
        if isTitle(it)
            if txt(it.kicker) = "Keep watching"
                bid = "play"
            else
                bid = "details"
            end if
        end if
    end if
    if bid = "play"
        act({ kind: "play", item: it })
    else if bid = "details"
        act({ kind: "details", item: it })
    else if bid = "more"
        act({ kind: "tool", id: "pick" })
    else if it.kind = "channel"
        act({ kind: "channel", id: it.id, name: it.title })
    else if it.kind = "tool"
        act({ kind: "tool", id: it.id })
    end if
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "up"
        if m.z > 0
            m.z = m.z - 1
            if m.z = 0 then m.catCol = m.cat
            if m.z = 2 and m.buttons.count() = 0 then m.z = 1
            paintAll(true)
        end if
    else if key = "down"
        if m.z < 3
            m.z = m.z + 1
            if m.z = 2 and m.buttons.count() = 0 then m.z = 3
            if m.z = 3 and m.cards.count() = 0 then m.z = 2
            paintAll(true)
        end if
    else if key = "left"
        if m.z = 0
            if m.catCol > 0
                m.catCol = m.catCol - 1
                if m.catCol < 2 then setCat(m.catCol)
                paintAll(true)
            end if
        else if m.z = 1
            moveTile(-1)
        else if m.z = 2
            if m.bcol > 0
                m.bcol = m.bcol - 1
                paintAll(true)
            end if
        else if m.ccol > 0
            m.ccol = m.ccol - 1
            paintAll(true)
        end if
    else if key = "right"
        if m.z = 0
            if m.catCol < m.CATS.count()
                m.catCol = m.catCol + 1
                if m.catCol < 2 then setCat(m.catCol)
                paintAll(true)
            end if
        else if m.z = 1
            moveTile(1)
        else if m.z = 2
            if m.bcol < m.buttons.count() - 1
                m.bcol = m.bcol + 1
                paintAll(true)
            end if
        else if m.ccol < m.cards.count() - 1
            m.ccol = m.ccol + 1
            paintAll(true)
        end if
    else if key = "OK"
        activate()
    else if key = "options"
        it = focusedItem()
        if it <> invalid and isTitle(it)
            act({ kind: "details", item: it })
        else
            act({ kind: "tool", id: "settings" })
        end if
    else if key = "play"
        it = focusedItem()
        if it <> invalid and isTitle(it) then act({ kind: "play", item: it })
    else if key = "back"
        if m.z <> 1
            m.z = 1
            paintAll(true)
        else if m.col > 0
            m.col = 0
            layoutStrip(true)
            paintAll(true)
        else
            return false
        end if
    else
        return false
    end if
    return true
end function
