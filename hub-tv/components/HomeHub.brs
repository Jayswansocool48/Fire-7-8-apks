' The "Hub" home look.
'   Top bar: your profile, tabs (For you, Shows, Movies, Anime, Apps, Search), weather and clock.
'   Featured: a title with its backdrop, where it streams on your TV, and Watch / Details buttons.
'   Rows: Keep watching, For you, your apps, Today (widgets), My Shows, YouTube, anime, Up next.
' Focusing a row card swaps the top area to that card's details and backdrop.

sub init()
    m.TAB_NAMES = ["For you", "Shows", "Movies", "Anime", "Apps"]
    m.tabIdx = 0
    m.zones = []
    m.z = 1
    m.heroItems = []
    m.heroIdx = 0
    m.detail = {}
    m.pending = {}
    m.bgUrl = ""
    m.bgFront = "bgA"
    m.focusedTile = invalid
    m.rowsY = 556
    m.searchSeq = -1
    m.touched = false

    m.fKicker = fontFile("semibold", 18)
    m.fTitle = fontFile("title", 52)
    m.fMeta = fontFile("medium", 20)
    m.fBody = fontFile("regular", 19)
    m.fSmall = fontFile("regular", 16)
    m.fSmallBold = fontFile("semibold", 16)
    m.fRow = fontFile("title", 24)
    m.fCard = fontFile("semibold", 18)
    m.fButton = fontFile("semibold", 19)
    m.fTab = fontFile("medium", 19)
    m.fChip = fontFile("medium", 16)

    m.top.findNode("kicker").font = m.fKicker
    m.top.findNode("title").font = m.fTitle
    m.top.findNode("meta").font = m.fMeta
    m.top.findNode("summary").font = m.fBody
    m.top.findNode("clockLbl").font = fontFile("semibold", 22)
    m.rows = m.top.findNode("rows")
    m.info = m.top.findNode("info")
    m.rows.translation = [0, m.rowsY]

    m.top.findNode("bgA").observeField("loadStatus", "onBgLoaded")
    m.top.findNode("bgB").observeField("loadStatus", "onBgLoaded")
    t = m.top.findNode("heroTimer")
    t.observeField("fire", "onHeroTimer")
    t.control = "start"
    m.top.findNode("settleTimer").observeField("fire", "onSettle")
    m.top.observeField("focusedChild", "onFocusChange")
end sub

sub onFocusChange()
    if m.top.hasFocus() then refocus(false)
end sub

sub onClockText()
    m.top.findNode("clockLbl").text = m.top.clock
end sub

' ---------------------------------------------------------------- data in

sub onData()
    d = m.top.data
    if d = invalid then return
    m.d = d
    m.server = d.server
    m.hub = d.hub
    m.st = d.state
    if m.st = invalid then m.st = {}
    keepZone = m.z
    keepCol = 0
    if m.zones.count() > keepZone and m.zones[keepZone] <> invalid then keepCol = m.zones[keepZone].col
    if d.search <> invalid and d.searchSeq <> m.searchSeq
        m.searchSeq = d.searchSeq
        m.tabIdx = -1
    end if
    buildTopbar()
    buildAll()
    if m.searchSeq <> -1 and m.tabIdx = -1 and m.zones.count() > 2
        keepZone = 2
        keepCol = 0
    end if
    if keepZone >= m.zones.count() then keepZone = m.zones.count() - 1
    ' until you move, Hub opens on the featured title
    if not m.touched
        keepZone = 1
        keepCol = 0
    end if
    m.z = keepZone
    if m.zones[m.z].kind = "hero" and m.heroItems.count() = 0 and m.zones.count() > 2 then m.z = 2
    z = m.zones[m.z]
    if keepCol > z.count - 1 then keepCol = z.count - 1
    if keepCol < 0 then keepCol = 0
    z.col = keepCol
    refocus(false)
end sub

' Which titles belong on the current tab.
function fitsTab(it as Object) as Boolean
    if m.tabIdx <= 0 then return true
    if m.tabIdx = 1 then return it.kind <> "movie" and not isAnime(it)
    if m.tabIdx = 2 then return it.kind = "movie"
    if m.tabIdx = 3 then return isAnime(it)
    return false
end function

function filtered(list as Object) as Object
    out = []
    for each it in list
        if fitsTab(it) then out.push(it)
    end for
    return out
end function

' ---------------------------------------------------------------- top bar

sub buildTopbar()
    pb = m.top.findNode("profileBtn")
    pb.removeChildrenIndex(pb.getChildCount(), 0)
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
    ring = uiPatch(pb, "pkg:/images/circle.png", -4, -4, 52, 52, pal("cream"))
    ring.id = "profileRing"
    ring.opacity = 0
    uiAvatar(pb, name, color, 0, 0, 44, fontFile("semibold", 20))
    nl = uiLabel(pb, name, 56, 0, 0, fontFile("medium", 19), pal("cream"))
    nl.height = 44
    nl.vertAlign = "center"

    tabs = m.top.findNode("tabs")
    tabs.removeChildrenIndex(tabs.getChildCount(), 0)
    names = []
    for each n in m.TAB_NAMES
        names.push(n)
    end for
    names.push("Search")
    widths = []
    total = 0
    for each n in names
        lbl = CreateObject("roSGNode", "Label")
        lbl.font = m.fTab
        lbl.text = n
        w = lbl.boundingRect().width + 36
        widths.push(w)
        total = total + w + 6
    end for
    x = Int((1280 - total) / 2)
    m.tabNodes = []
    for i = 0 to names.count() - 1
        g = tabs.createChild("Group")
        g.id = "tab" + i.toStr()
        g.translation = [x, 2]
        bg = uiPatch(g, "pkg:/images/pill.9.png", 0, 0, widths[i], 40, pal("cream"))
        bg.opacity = 0
        lbl = uiLabel(g, names[i], 0, 0, widths[i], m.fTab, pal("mist"))
        lbl.height = 40
        lbl.horizAlign = "center"
        lbl.vertAlign = "center"
        bar = uiPatch(g, "pkg:/images/bar.9.png", 18, 40, widths[i] - 36, 4, rokuColor(m.hub.accent, "FF"))
        bar.opacity = 0
        m.tabNodes.push(g)
        x = x + widths[i] + 6
    end for
    paintTabs(false)

    wg = m.top.findNode("weather")
    wg.removeChildrenIndex(wg.getChildCount(), 0)
    w = invalid
    if m.st.widgets <> invalid then w = m.st.widgets.weather
    if w <> invalid
        ic = uiImage(wg, "pkg:/images/w_" + w.icon + ".png", 0, 0, 34, 34)
        ic.blendColor = pal("cream")
        t = uiLabel(wg, w.temp.toStr() + Chr(176), 42, 0, 0, fontFile("semibold", 22), pal("cream"))
        t.height = 34
        t.vertAlign = "center"
        tw = t.boundingRect().width
        wg.translation = [1040 - 42 - tw - 24, 6]
    end if
    m.top.findNode("clockLbl").text = m.top.clock
end sub

' tabs: the selected one gets an accent underline; the focused one turns cream
sub paintTabs(focusOn as Boolean)
    if m.tabNodes = invalid then return
    col = -99
    if focusOn then col = m.zones[0].col - 1
    for i = 0 to m.tabNodes.count() - 1
        g = m.tabNodes[i]
        bg = g.getChild(0)
        lbl = g.getChild(1)
        bar = g.getChild(2)
        selected = i = m.tabIdx
        if i = col
            animTo(bg, "opacity", 1.0, "snappy")
            lbl.color = pal("ink")
        else
            animTo(bg, "opacity", 0.0, "snappy")
            if selected
                lbl.color = pal("cream")
            else
                lbl.color = pal("mist")
            end if
        end if
        if selected and i <> col
            bar.opacity = 1
        else
            bar.opacity = 0
        end if
    end for
    ring = m.top.findNode("profileRing")
    if ring <> invalid
        if col = -1
            animTo(ring, "opacity", 1.0, "snappy")
        else
            animTo(ring, "opacity", 0.0, "snappy")
        end if
    end if
end sub

' ---------------------------------------------------------------- building rows

sub buildAll()
    m.rows.removeChildrenIndex(m.rows.getChildCount(), 0)
    m.focusedTile = invalid
    m.zones = [{ kind: "tabs", col: m.tabIdx + 1, count: m.TAB_NAMES.count() + 2 }]
    buildHeroItems()
    m.zones.push({ kind: "hero", col: 0, count: 0, buttons: [] })
    y = 0
    for each r in rowPlan()
        if r <> invalid and r.items.count() > 0
            row = makeRow(r, y)
            m.zones.push(row)
            y = y + row.height
        end if
    end for
    if m.zones.count() = 2 and m.heroItems.count() = 0
        ' nothing at all yet: a hint row so Down has somewhere to go
        row = makeRow({ id: "empty", title: "Getting started", card: "widget", items: [{ kind: "hint", title: "Add shows from Hub Remote", text: "Search on the tablet or the control page and save what you watch. Picks and Keep watching fill in from there." }] }, y)
        m.zones.push(row)
    end if
    renderHero(false)
end sub

' Rows for the current tab, in the order you set on the control page.
function rowPlan() as Object
    st = m.st
    w = st.widgets
    if w = invalid then w = {}
    plan = []
    if m.tabIdx = -1
        plan.push({ id: "search", title: "Search results", card: "poster", items: listOf(m.d.search) })
        return plan
    end if
    if m.tabIdx = 4
        return appRows()
    end if
    continueItems = filtered(listOf(st.history))
    forYou = filtered(listOf(st.foryou))
    mine = filtered(listOf(st.shows))
    today = todayItems()
    order = ["continue", "foryou", "apps", "myshows", "youtube", "anime", "agenda"]
    if m.hub.rows <> invalid and m.hub.rows.count() > 0
        order = []
        for each r in m.hub.rows
            if isTrue(r.on) then order.push(r.id)
        end for
    end if
    mode = m.d.mode
    if m.tabIdx = 0 and (mode = "morning" or mode = "night") then plan.push({ id: "today", title: todayTitle(), card: "widget", items: today })
    for each id in order
        if id = "continue"
            plan.push({ id: "continue", title: "Keep watching", card: "wide", items: continueItems })
        else if id = "foryou"
            plan.push({ id: "foryou", title: "For you", card: "poster", items: forYou })
        else if id = "apps" and m.tabIdx = 0
            plan.push({ id: "apps", title: "Your apps", card: "app", items: appItems(10, false) })
            if mode <> "morning" and mode <> "night" then plan.push({ id: "today", title: todayTitle(), card: "widget", items: today })
        else if id = "myshows"
            plan.push({ id: "myshows", title: "My Shows", card: "poster", items: mine })
        else if id = "youtube" and m.tabIdx = 0
            plan.push({ id: "youtube", title: "New on YouTube", card: "video", items: listOf(w.youtube) })
        else if id = "anime" and (m.tabIdx = 0 or m.tabIdx = 3)
            plan.push({ id: "anime", title: "Anime this week", card: "anime", items: listOf(w.anime) })
        else if id = "agenda" and m.tabIdx = 0
            plan.push({ id: "agenda", title: "Up next", card: "event", items: listOf(w.agenda) })
        end if
    end for
    return plan
end function

function appRows() as Object
    all = appItems(0, false)
    plan = []
    per = 5
    i = 0
    n = 0
    while i < all.count()
        chunk = []
        j = i
        while j < all.count() and j < i + per
            chunk.push(all[j])
            j = j + 1
        end while
        title = ""
        if n = 0 then title = "Your channels"
        plan.push({ id: "apps" + n.toStr(), title: title, card: "app", items: chunk })
        i = j
        n = n + 1
    end while
    tools = []
    for each t in m.d.tools
        tools.push({ kind: "tool", id: t.id, title: t.name, icon: t.icon })
    end for
    plan.push({ id: "tools", title: "Hub", card: "app", items: tools })
    return plan
end function

function cardSize(card as String) as Object
    if card = "wide" or card = "video" then return { w: 300, h: 169, s: 1.08, mask: "pkg:/images/mask_thumb.png" }
    if card = "poster" then return { w: 150, h: 225, s: 1.08, mask: "pkg:/images/mask_poster.png" }
    if card = "anime" then return { w: 150, h: 212, s: 1.08, mask: "pkg:/images/mask_cover.png" }
    if card = "app" then return { w: 200, h: 113, s: 1.1, mask: "pkg:/images/mask_thumb.png" }
    if card = "event" then return { w: 280, h: 120, s: 1.05, mask: "" }
    return { w: 280, h: 158, s: 1.05, mask: "" }
end function

function makeRow(r as Object, y as Integer) as Object
    sz = cardSize(r.card)
    g = m.rows.createChild("Group")
    g.id = "row_" + r.id
    g.translation = [0, y]
    top = 0
    if r.title <> ""
        t = uiLabel(g, r.title, 80, 0, 0, m.fRow, "0xECEDF0E6")
        top = 42
    end if
    clip = g.createChild("Group")
    clip.translation = [80, top]
    clip.clippingRect = [-80, -40, 1280, sz.h + 90]
    cards = clip.createChild("Group")
    cards.id = "cards_" + r.id
    row = { kind: "row", id: r.id, title: r.title, card: r.card, group: g, cardsGroup: cards, tiles: [], xs: [], items: r.items, col: 0, count: 0, offset: 0, w: sz.w, h: sz.h, s: sz.s, y: y, height: top + sz.h + 44, top: top }
    x = 0
    for i = 0 to r.items.count() - 1
        it = r.items[i]
        if it.kind = invalid then it.kind = r.card
        tile = uiTile(cards, "t_" + r.id + "_" + i.toStr(), sz.w, sz.h, tileFace(r.card))
        fillTile(tile, r.card, it, sz)
        tile.translation = [x, 0]
        row.tiles.push(tile)
        row.xs.push(x)
        x = x + sz.w + 22
    end for
    row.count = row.tiles.count()
    return row
end function

function tileFace(card as String) as String
    if card = "widget" or card = "event" then return pal("panel")
    if card = "app" then return pal("raise")
    return "0xECEDF014"
end function

sub fillTile(tile as Object, card as String, it as Object, sz as Object)
    w = sz.w
    h = sz.h
    if card = "poster" or card = "anime"
        img = it.poster
        if card = "anime" then img = it.cover
        uiArt(tile, thumbUrl(m.server, img), sz.mask, w, h, card = "anime")
        if card = "anime"
            sub1 = ""
            if it.episode <> invalid then sub1 = "Ep " + it.episode.toStr()
            uiLabel(tile, sub1, 12, h - 54, w - 24, m.fSmallBold, pal("cream"))
            if it.airingAt <> invalid then uiLabel(tile, untilText(it.airingAt - CreateObject("roDateTime").asSeconds()), 12, h - 30, w - 24, m.fSmall, "0xECEDF0C0")
        else if it.poster = invalid or it.poster = ""
            t = uiLabel(tile, it.title, 14, 16, w - 28, fontFile("title", 22), pal("cream"))
            t.wrap = true
            t.maxLines = 4
        end if
    else if card = "wide"
        art = it.backdrop
        if art = invalid or art = "" then art = it.poster
        uiArt(tile, thumbUrl(m.server, art), sz.mask, w, h, true)
        t = uiLabel(tile, it.title, 16, h - 60, w - 32, m.fCard, pal("cream"))
        t.maxLines = 1
        sub2 = agoOf(it.at)
        if it.appName <> invalid and it.appName <> "" then sub2 = it.appName + "  " + Chr(183) + "  " + sub2
        uiLabel(tile, sub2, 16, h - 34, w - 32, m.fSmall, "0xECEDF0C0")
    else if card = "video"
        uiArt(tile, thumbUrl(m.server, it.thumb), sz.mask, w, h, true)
        t = uiLabel(tile, it.title, 16, h - 66, w - 32, m.fSmallBold, pal("cream"))
        t.wrap = true
        t.maxLines = 2
        uiLabel(tile, it.channel, 16, h - 26, w - 32, m.fSmall, "0xECEDF0B0")
    else if card = "app"
        if it.kind = "channel"
            mk = tile.createChild("MaskGroup")
            mk.maskUri = sz.mask
            mk.maskSize = [w, h]
            p = mk.createChild("Poster")
            p.uri = serverUrl(m.server) + "/api/tv/icon/" + urlEncode(it.id, false)
            p.width = w
            p.height = h
            p.loadDisplayMode = "scaleToFill"
        else
            ic = uiImage(tile, it.icon, Int(w / 2) - 22, 22, 44, 44)
            ic.blendColor = pal("cream")
            l = uiLabel(tile, it.title, 0, 74, w, m.fSmallBold, pal("cream"))
            l.horizAlign = "center"
        end if
    else if card = "event"
        uiLabel(tile, dayAndTime(it.start, isTrue(m.hub.clock24), isTrue(it.allDay)), 20, 18, w - 40, m.fSmallBold, rokuColor(m.hub.accent, "FF"))
        t = uiLabel(tile, it.title, 20, 46, w - 40, m.fCard, pal("cream"))
        t.wrap = true
        t.maxLines = 2
    else
        fillWidget(tile, it, w, h)
    end if
end sub

' ---------------------------------------------------------------- featured titles

sub buildHeroItems()
    m.heroItems = []
    if m.tabIdx = 4 or m.tabIdx = -1 then return
    hist = filtered(listOf(m.st.history))
    now = CreateObject("roDateTime").asSeconds()
    if hist.count() > 0 and hist[0].at <> invalid and now - hist[0].at < 4 * 86400
        h = hist[0]
        h.kicker = "Keep watching"
        m.heroItems.push(h)
    end if
    for each it in filtered(listOf(m.st.foryou))
        if m.heroItems.count() >= 5 then exit for
        it.kicker = "Hub recommends"
        m.heroItems.push(it)
    end for
    if m.heroItems.count() = 0
        for each it in filtered(listOf(m.st.shows))
            if m.heroItems.count() >= 3 then exit for
            it.kicker = "From My Shows"
            m.heroItems.push(it)
        end for
    end if
    if m.heroIdx >= m.heroItems.count() then m.heroIdx = 0
end sub

sub renderHero(animate as Boolean)
    hz = m.zones[1]
    hb = m.top.findNode("heroButtons")
    hb.removeChildrenIndex(hb.getChildCount(), 0)
    hz.buttons = []
    hz.count = 0
    if m.heroItems.count() = 0 then return
    it = m.heroItems[m.heroIdx]
    det = detailFor(it)
    watch = "Watch"
    if det <> invalid and det.watchOn <> invalid
        watch = "Watch on " + det.watchOn.appName
    else if it.appName <> invalid and it.appName <> ""
        watch = "Watch on " + it.appName
    end if
    specs = [{ id: "play", text: watch, icon: "pkg:/images/i_play.png" }, { id: "details", text: "Details", icon: "pkg:/images/i_info.png" }, { id: "more", text: "More picks", icon: "" }]
    hz.buttons = uiButtons(hb, "hb", specs, m.fButton)
    hz.count = hz.buttons.count()
    dots = m.top.findNode("dots")
    dots.removeChildrenIndex(dots.getChildCount(), 0)
    if m.heroItems.count() > 1
        for i = 0 to m.heroItems.count() - 1
            c = "0xECEDF055"
            sz = 8
            if i = m.heroIdx
                c = pal("cream")
                sz = 10
            end if
            uiPatch(dots, "pkg:/images/circle.png", i * 20, 0, sz, sz, c)
        end for
    end if
    if m.z = 1 then showInfo(it, "hero")
    if det = invalid then requestDetail(it)
end sub

sub onHeroTimer()
    if m.heroItems.count() < 2 or m.z <> 1 or not m.top.isInFocusChain() then return
    m.heroIdx = (m.heroIdx + 1) mod m.heroItems.count()
    renderHero(true)
    focusButton(m.zones[1], m.zones[1].col)
end sub

' ---------------------------------------------------------------- the info area at the top

' Fills the top area for a title, app or widget.
sub showInfo(it as Object, where as String)
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
    k = ""
    if where = "hero"
        k = txt(it.kicker)
        if txt(it.reason) <> "" and k = "Hub recommends" then k = it.reason
    else
        z = m.zones[m.z]
        if z.title <> invalid then k = z.title
    end if
    if k = invalid then k = ""
    kick.text = UCase(k)
    title.text = it.title
    if isTitle(it)
        line = metaLine(src)
        if det <> invalid
            if det.seasons <> invalid and det.seasons.count() > 0
                s = det.seasons.count()
                if s = 1
                    line = line + "  " + Chr(183) + "  1 season"
                else
                    line = line + "  " + Chr(183) + "  " + s.toStr() + " seasons"
                end if
            else if det.runtime <> invalid and det.kind = "movie"
                line = line + "  " + Chr(183) + "  " + det.runtime.toStr() + " min"
            end if
        end if
        meta.text = line
        body = txt(src.summary)
        if body = "" then body = txt(it.reason)
        summary.text = body
    else
        meta.text = itemMeta(it)
        summary.text = itemSummary(it)
    end if
    if where = "row"
        summary.maxLines = 2
    else
        summary.maxLines = 3
    end if
    ' lay the pieces out under the title
    y = 30 + title.boundingRect().height + 10
    meta.translation = [2, y]
    y = y + 34
    aw = 0
    if isTitle(it) then aw = drawAvail(avail, it, det)
    avail.translation = [0, y]
    if aw > 0 then y = y + 48
    summary.translation = [2, y]
    if summary.text <> "" then y = y + summary.boundingRect().height + 22
    hb = m.top.findNode("heroButtons")
    hb.translation = [0, y]
    dots = m.top.findNode("dots")
    dots.translation = [0, y + 74]
    if where = "hero"
        hb.opacity = 1
        dots.opacity = 1
    else
        hb.opacity = 0
        dots.opacity = 0
    end if
    bg = ""
    if src.backdrop <> invalid and src.backdrop <> "" then bg = src.backdrop
    if bg = "" and it.kind = "channel" then bg = ""
    setBackdrop(bg)
end sub

' ---------------------------------------------------------------- focus

function focusedItem() as Dynamic
    if m.zones.count() = 0 then return invalid
    z = m.zones[m.z]
    if z.kind = "hero"
        if m.heroItems.count() = 0 then return invalid
        return m.heroItems[m.heroIdx]
    end if
    if z.kind = "row" and z.col < z.items.count() then return z.items[z.col]
    return invalid
end function

sub refocus(animate as Boolean)
    if m.zones.count() = 0 then return
    z = m.zones[m.z]
    paintTabs(z.kind = "tabs")
    if z.kind = "row"
        focusCard(z, z.col, animate)
    else
        if m.focusedTile <> invalid then uiFocus(m.focusedTile, false, 1.0)
        m.focusedTile = invalid
    end if
    if z.kind = "hero"
        focusButton(z, z.col)
        if m.heroItems.count() > 0 then showInfo(m.heroItems[m.heroIdx], "hero")
    else
        focusButton(m.zones[1], -1)
    end if
    scrollPage(animate)
end sub

sub focusButton(z as Object, col as Integer)
    for i = 0 to z.buttons.count() - 1
        uiButtonFocus(z.buttons[i], i = col and m.z = 1)
    end for
end sub

sub focusCard(row as Object, col as Integer, animate as Boolean)
    if col < 0 or col >= row.count then return
    tile = row.tiles[col]
    if m.focusedTile <> invalid and not m.focusedTile.isSameNode(tile) then uiFocus(m.focusedTile, false, 1.0)
    uiFocus(tile, true, row.s)
    m.focusedTile = tile
    row.col = col
    ' keep the card in view
    x = row.xs[col]
    target = row.offset
    if x + target > 1120 - row.w then target = 1120 - row.w - x
    if x + target < 0 then target = -x
    if target <> row.offset
        row.offset = target
        if animate
            animTo(row.cardsGroup, "translation", [target, 0], "smooth")
        else
            row.cardsGroup.translation = [target, 0]
        end if
    end if
    it = row.items[col]
    showInfo(it, "row")
    st = m.top.findNode("settleTimer")
    st.control = "stop"
    st.control = "start"
end sub

sub detailReady(cur as Object)
    if m.z = 1
        renderHero(false)
        focusButton(m.zones[1], m.zones[1].col)
    else
        showInfo(cur, "row")
    end if
end sub

sub onSettle()
    it = focusedItem()
    if it <> invalid and isTitle(it) then requestDetail(it)
end sub

' The rows slide up under the info area as you move down.
sub scrollPage(animate as Boolean)
    z = m.zones[m.z]
    y = 556
    deck = 0.0
    if z.kind = "row"
        y = 420 - z.y
        deck = 0.35
    end if
    clipTop = 404
    if m.tabIdx = 4 or m.tabIdx = -1
        if z.kind = "row" then y = 380 - z.y
        clipTop = 364
    end if
    m.top.findNode("rowsClip").clippingRect = [0, clipTop, 1280, 720 - clipTop]
    if animate
        animTo(m.rows, "translation", [0, y], "smooth")
        animTo(m.top.findNode("deck"), "opacity", deck, "smooth")
    else
        m.rows.translation = [0, y]
        m.top.findNode("deck").opacity = deck
    end if
    m.rowsY = y
end sub

sub moveZone(delta as Integer)
    n = m.z + delta
    if n < 0 or n >= m.zones.count() then return
    if m.zones[n].kind = "hero" and m.zones[n].count = 0
        n = n + delta
        if n < 0 or n >= m.zones.count() then return
    end if
    cur = m.zones[m.z]
    target = m.zones[n]
    if target.kind = "row" and cur.kind = "row"
        ' land on the card nearest the one you were on
        x = cur.xs[cur.col] + cur.offset + cur.w / 2
        best = 0
        bestD = 99999
        for i = 0 to target.count - 1
            d = Abs(target.xs[i] + target.offset + target.w / 2 - x)
            if d < bestD
                bestD = d
                best = i
            end if
        end for
        target.col = best
    end if
    if target.kind = "hero" then target.col = 0
    m.z = n
    refocus(true)
end sub

sub switchTab(idx as Integer)
    if idx = m.tabIdx then return
    m.tabIdx = idx
    m.heroIdx = 0
    buildTopbar()
    buildAll()
    m.zones[0].col = idx + 1
    m.z = 0
    refocus(false)
end sub

' ---------------------------------------------------------------- actions

sub activate()
    z = m.zones[m.z]
    if z.kind = "tabs"
        c = z.col
        if c = 0
            act({ kind: "profile" })
        else if c = m.TAB_NAMES.count() + 1
            act({ kind: "search" })
        else
            m.z = 1
            if m.zones[1].count = 0 then m.z = 2
            if m.z >= m.zones.count() then m.z = 0
            refocus(true)
        end if
        return
    end if
    if z.kind = "hero"
        it = m.heroItems[m.heroIdx]
        b = z.buttons[z.col]
        if b.id = "play"
            act({ kind: "play", item: it })
        else if b.id = "details"
            act({ kind: "details", item: it })
        else
            act({ kind: "tool", id: "pick" })
        end if
        return
    end if
    it = z.items[z.col]
    k = it.kind
    tile = z.tiles[z.col]
    springPressTile(tile, z.s)
    if z.id = "continue"
        act({ kind: "play", item: it })
    else if isTitle(it)
        act({ kind: "details", item: it })
    else if k = "channel"
        act({ kind: "channel", id: it.id, name: it.title })
    else if k = "tool"
        act({ kind: "tool", id: it.id })
    else if it.thumb <> invalid
        act({ kind: "video", video: it })
    else if it.cover <> invalid
        act({ kind: "details", item: { title: it.title, kind: "tv", anime: true, anilist: it.id, poster: it.cover } })
    else if k = "last"
        act({ kind: "play", item: it.item })
    else if k = "sleep"
        act({ kind: "sleep", minutes: 30 })
    else if k = "music"
        act({ kind: "tool", id: "music" })
    else if k = "weather"
        act({ kind: "tool", id: "ask" })
    end if
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press or m.zones.count() = 0 then return false
    m.touched = true
    z = m.zones[m.z]
    if key = "up"
        moveZone(-1)
    else if key = "down"
        moveZone(1)
    else if key = "left"
        if z.kind = "tabs"
            if z.col > 0
                z.col = z.col - 1
                paintTabs(true)
                if z.col >= 1 and z.col <= m.TAB_NAMES.count() then switchTab(z.col - 1)
            end if
        else if z.kind = "hero"
            if z.col > 0
                z.col = z.col - 1
                focusButton(z, z.col)
            else if m.heroItems.count() > 1
                m.heroIdx = (m.heroIdx - 1 + m.heroItems.count()) mod m.heroItems.count()
                renderHero(true)
                focusButton(z, z.col)
            end if
        else if z.col > 0
            focusCard(z, z.col - 1, true)
        end if
    else if key = "right"
        if z.kind = "tabs"
            if z.col < z.count - 1
                z.col = z.col + 1
                paintTabs(true)
                if z.col >= 1 and z.col <= m.TAB_NAMES.count() then switchTab(z.col - 1)
            end if
        else if z.kind = "hero"
            if z.col < z.count - 1
                z.col = z.col + 1
                focusButton(z, z.col)
            else if m.heroItems.count() > 1
                m.heroIdx = (m.heroIdx + 1) mod m.heroItems.count()
                renderHero(true)
                focusButton(z, z.col)
            end if
        else if z.col < z.count - 1
            focusCard(z, z.col + 1, true)
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
        if it <> invalid and isTitle(it)
            act({ kind: "play", item: it })
        else
            act({ kind: "tool", id: "pick" })
        end if
    else if key = "back"
        if m.z > 1
            m.z = 1
            if m.zones[1].count = 0 then m.z = 0
            refocus(true)
        else if m.tabIdx <> 0
            switchTab(0)
        else
            return false
        end if
    else
        return false
    end if
    return true
end function
