' One title, in full. The Hub server gathers the details (TVmaze, AniList, Apple, Wikidata,
' and TMDB with a key) and says which of your channels stream it and with what ID, so
' Watch opens the show itself instead of just the app.

sub init()
    m.fKicker = fontFile("semibold", 18)
    m.fMeta = fontFile("medium", 20)
    m.fBody = fontFile("regular", 19)
    m.fSmall = fontFile("regular", 16)
    m.fSmallBold = fontFile("semibold", 16)
    m.fButton = fontFile("semibold", 19)
    m.fChip = fontFile("medium", 16)
    m.fSection = fontFile("title", 26)
    m.top.findNode("kicker").font = m.fKicker
    m.top.findNode("title").font = fontFile("title", 54)
    m.top.findNode("meta").font = m.fMeta
    m.top.findNode("extra").font = m.fSmall
    m.top.findNode("summary").font = m.fBody
    m.top.findNode("hint").font = m.fSmall
    m.top.findNode("hint").text = "Back to Hub"
    m.page = m.top.findNode("page")
    m.zones = []
    m.z = 0
    m.det = invalid
    m.item = invalid
    m.saved = false
    m.top.findNode("bg").observeField("loadStatus", "onBgLoaded")
end sub

sub onParams()
    p = m.top.params
    if p = invalid or p.item = invalid then return
    m.item = p.item
    m.accent = rokuColor(p.accent, "FF")
    m.det = invalid
    m.seasonIdx = 0
    m.episodes = []
    m.top.findNode("kicker").color = m.accent
    renderHead()
    skeleton(true)
    if m.top.server <> ""
        startRequest(serverUrl(m.top.server) + "/api/title" + titleQuery(m.item), "GET", "", "onDetail")
    end if
end sub

sub onDetail(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    skeleton(false)
    if data = invalid or data.title = invalid
        m.top.findNode("extra").text = "Hub couldn't find more about this title."
        buildButtons()
        refocus(false)
        return
    end if
    m.det = data.title
    m.saved = isTrue(m.det.saved)
    renderHead()
    buildSections()
    refocus(false)
    if m.det.seasons <> invalid and m.det.seasons.count() > 0 and not isTrue(m.det.anime) then loadSeason(m.det.seasons.count() - 1)
end sub

function src() as Object
    if m.det <> invalid then return m.det
    return m.item
end function

' ---------------------------------------------------------------- the top part

sub renderHead()
    s = src()
    kick = m.top.findNode("kicker")
    parts = []
    if s.kind = "movie"
        parts.push("Movie")
    else if isTrue(s.anime) or (s.genres <> invalid and joinText(s.genres, ",").instr("Anime") >= 0)
        parts.push("Anime series")
    else
        parts.push("Show")
    end if
    if txt(s.network) <> "" then parts.push(s.network)
    if txt(s.studio) <> "" then parts.push(s.studio)
    kick.text = UCase(joinText(parts, "  " + Chr(183) + "  "))
    title = m.top.findNode("title")
    title.text = s.title
    meta = m.top.findNode("meta")
    mp = []
    if txt(s.year) <> "" then mp.push(txt(s.year))
    if s.seasons <> invalid and s.seasons.count() > 0
        n = s.seasons.count()
        if n = 1
            mp.push("1 season")
        else
            mp.push(n.toStr() + " seasons")
        end if
    end if
    if s.runtime <> invalid then mp.push(txt(s.runtime) + " min")
    if s.rating <> invalid then mp.push(Chr(9733) + " " + txt(s.rating))
    if txt(s.rated) <> "" then mp.push(s.rated)
    if txt(s.status) <> "" and s.kind <> "movie" then mp.push(s.status)
    meta.text = joinText(mp, "  " + Chr(183) + "  ")
    extra = m.top.findNode("extra")
    ex = []
    if s.genres <> invalid and s.genres.count() > 0 then ex.push(joinText(s.genres, ", "))
    if txt(s.director) <> "" then ex.push("Directed by " + s.director)
    if s.nextEpisode <> invalid then ex.push("Episode " + txt(s.nextEpisode.episode) + " " + untilText(s.nextEpisode.airingAt - CreateObject("roDateTime").asSeconds()))
    extra.text = joinText(ex, "  " + Chr(183) + "  ")
    summary = m.top.findNode("summary")
    body = txt(s.summary)
    if txt(s.tagline) <> "" then body = s.tagline + "  " + body
    summary.text = body

    y = 28 + title.boundingRect().height + 10
    meta.translation = [2, y]
    y = y + 32
    extra.translation = [2, y]
    if extra.text <> "" then y = y + 30
    avail = m.top.findNode("avail")
    avail.removeChildrenIndex(avail.getChildCount(), 0)
    avail.translation = [0, y + 6]
    if m.det <> invalid
        h = drawProviders(avail)
        y = y + 6 + h + 14
    end if
    summary.translation = [2, y]
    if body <> "" then y = y + summary.boundingRect().height + 24
    if m.det = invalid then y = y + 130 ' room for the loading bars
    m.top.findNode("buttons").translation = [0, y]
    m.headBottom = 64 + y + 70
    buildButtons()

    pb = m.top.findNode("posterBox")
    pb.removeChildrenIndex(pb.getChildCount(), 0)
    if txt(s.poster) <> ""
        uiPatch(pb, "pkg:/images/card.9.png", -6, -6, 282, 420, "0x00000055")
        uiArt(pb, thumbUrl(m.top.server, s.poster), "pkg:/images/mask_poster.png", 270, 405, false)
    end if
    art = txt(s.backdrop)
    if art <> ""
        bg = m.top.findNode("bg")
        u = thumbUrl(m.top.server, art)
        if bg.uri <> u
            bg.opacity = 0
            bg.uri = u
        end if
    end if
end sub

sub onBgLoaded()
    bg = m.top.findNode("bg")
    if bg.loadStatus = "ready" then animTo(bg, "opacity", 1.0, "gentle")
end sub

' "Watch on" chips: your channels first (a play mark when Hub can open the title directly),
' then services you'd need to install. Returns the height used.
function drawProviders(parent as Object) as Integer
    provs = m.det.providers
    if provs = invalid or provs.count() = 0
        l = uiLabel(parent, "Hub didn't find where this streams. Watch searches all your channels.", 0, 0, 760, m.fChip, pal("mist"))
        l.height = 34
        l.vertAlign = "center"
        return 34
    end if
    x = 0
    rowY = 0
    others = []
    for each p in provs
        if isTrue(p.installed)
            label = p.appName
            if txt(p.contentId) <> "" then label = Chr(9654) + "  " + label
            cw = uiChip(parent, label, x, rowY, m.fChip, pal("cream"), pal("ink"))
            x = x + cw + 10
        else
            others.push(p.name)
        end if
    end for
    if others.count() > 0
        line = "Not on your TV: " + joinText(others, ", ")
        if x = 0 then line = "Streams on " + joinText(others, ", ") + ". Add one of these channels on the TV to watch it."
        if x > 0 and x > 420
            rowY = 44
            x = 0
        end if
        l = uiLabel(parent, line, x + 4, rowY, 760 - x, m.fChip, pal("mist"))
        l.height = 34
        l.vertAlign = "center"
    end if
    return rowY + 34
end function

sub buildButtons()
    g = m.top.findNode("buttons")
    g.removeChildrenIndex(g.getChildCount(), 0)
    specs = []
    main = invalid
    alts = []
    if m.det <> invalid and m.det.providers <> invalid
        for each p in m.det.providers
            if isTrue(p.installed)
                if main = invalid
                    main = p
                else if alts.count() < 2
                    alts.push(p)
                end if
            end if
        end for
    end if
    if main <> invalid
        specs.push({ id: "play", text: "Watch on " + main.appName, icon: "pkg:/images/i_play.png", provider: main.name })
    else
        specs.push({ id: "play", text: "Find it on the TV", icon: "pkg:/images/i_search.png", provider: "" })
    end if
    for each a in alts
        specs.push({ id: "play", text: a.appName, icon: "", provider: a.name })
    end for
    if m.saved
        specs.push({ id: "save", text: "In My Shows", icon: "pkg:/images/i_check.png" })
    else
        specs.push({ id: "save", text: "My Shows", icon: "pkg:/images/i_plus.png" })
    end if
    m.btns = uiButtons(g, "db", specs, m.fButton)
    for i = 0 to specs.count() - 1
        m.btns[i].provider = specs[i].provider
    end for
    if m.zones.count() = 0
        m.zones = [{ kind: "buttons", col: 0, count: m.btns.count(), y: 0 }]
    else
        m.zones[0].count = m.btns.count()
        if m.zones[0].col >= m.btns.count() then m.zones[0].col = 0
    end if
end sub

sub skeleton(on as Boolean)
    g = m.top.findNode("skeleton")
    g.removeChildrenIndex(g.getChildCount(), 0)
    if not on then return
    y = m.top.findNode("summary").translation[1]
    for each bar in [[0, 600], [40, 560], [80, 420]]
        uiPatch(g, "pkg:/images/bar.9.png", 2, y + bar[0], bar[1], 18, pal("raise"))
    end for
end sub

' ---------------------------------------------------------------- seasons, episodes, cast, more like this

sub buildSections()
    sec = m.top.findNode("sections")
    sec.removeChildrenIndex(sec.getChildCount(), 0)
    keep = m.zones[0]
    m.zones = [keep]
    y = m.headBottom + 30
    if y < 560 then y = 560
    d = m.det
    if isTrue(d.anime) and d.episodes <> invalid and d.episodes.count() > 0
        y = addRow(sec, "eps", "Episodes", d.episodes, "episode", y)
    else if d.seasons <> invalid and d.seasons.count() > 0
        chips = []
        for each s in d.seasons
            chips.push({ text: "Season " + txt(s.number), id: s.id })
        end for
        y = addChips(sec, "seasons", "Seasons", chips, y)
        m.epsY = y
        y = y + 260
    end if
    if d.cast <> invalid and d.cast.count() > 0 then y = addRow(sec, "cast", "Cast", d.cast, "person", y)
    if d.similar <> invalid and d.similar.count() > 0 then y = addRow(sec, "similar", "More like this", d.similar, "poster", y)
end sub

function addChips(parent as Object, id as String, title as String, chips as Object, y as Integer) as Integer
    g = parent.createChild("Group")
    g.translation = [80, y]
    uiLabel(g, title, 0, 0, 0, m.fSection, pal("cream"))
    holder = g.createChild("Group")
    holder.translation = [0, 46]
    specs = []
    for each c in chips
        specs.push({ id: c.id, text: c.text })
    end for
    btns = uiButtons(holder, "sc", specs, m.fChip)
    m.zones.push({ kind: "chips", id: id, col: 0, count: btns.count(), btns: btns, items: chips, y: y, group: holder })
    return y + 120
end function

function addRow(parent as Object, id as String, title as String, items as Object, card as String, y as Integer) as Integer
    g = parent.createChild("Group")
    g.id = "sec_" + id
    g.translation = [0, y]
    uiLabel(g, title, 80, 0, 0, m.fSection, pal("cream"))
    clip = g.createChild("Group")
    clip.translation = [80, 48]
    w = 150
    h = 225
    if card = "episode"
        w = 260
        h = 146
    else if card = "person"
        w = 120
        h = 120
    end if
    clip.clippingRect = [-80, -30, 1280, h + 110]
    cards = clip.createChild("Group")
    cards.id = "cards_" + id
    tiles = []
    xs = []
    x = 0
    n = items.count()
    if n > 30 then n = 30
    for i = 0 to n - 1
        it = items[i]
        face = "0xF2EADC14"
        t = uiTile(cards, "d_" + id + "_" + i.toStr(), w, h, face)
        if card = "episode"
            img = txt(it.image)
            if img <> "" then uiArt(t, thumbUrl(m.top.server, img), "pkg:/images/mask_thumb.png", w, h, true)
            label = txt(it.name)
            if it.number <> invalid then label = "E" + txt(it.number) + "  " + label
            l = uiLabel(t, label, 12, h - 54, w - 24, m.fSmallBold, pal("cream"))
            l.wrap = true
            l.maxLines = 2
            if txt(it.airdate) <> "" then uiLabel(t, it.airdate, 0, h + 10, w, m.fSmall, pal("mist"))
        else if card = "person"
            t.getChild(1).uri = "pkg:/images/circle.png"
            t.getChild(1).blendColor = pal("raise")
            t.getChild(0).uri = "pkg:/images/circle.png"
            t.getChild(0).blendColor = pal("cream")
            if txt(it.photo) <> "" then uiArt(t, thumbUrl(m.top.server, it.photo), "pkg:/images/mask_circle.png", w, h, false)
            nl = uiLabel(t, it.name, -20, h + 10, w + 40, m.fSmallBold, pal("cream"))
            nl.horizAlign = "center"
            nl.maxLines = 1
            rl = uiLabel(t, txt(it.role), -20, h + 34, w + 40, m.fSmall, pal("mist"))
            rl.horizAlign = "center"
            rl.maxLines = 1
        else
            if txt(it.poster) <> ""
                uiArt(t, thumbUrl(m.top.server, it.poster), "pkg:/images/mask_poster.png", w, h, false)
            else
                l = uiLabel(t, txt(it.title), 12, 14, w - 24, m.fSmallBold, pal("cream"))
                l.wrap = true
                l.maxLines = 4
            end if
        end if
        t.translation = [x, 0]
        tiles.push(t)
        xs.push(x)
        x = x + w + 22
    end for
    m.zones.push({ kind: "row", id: id, card: card, col: 0, count: tiles.count(), tiles: tiles, xs: xs, items: items, w: w, h: h, y: y, offset: 0, cardsGroup: cards })
    extraH = 70
    if card = "person" then extraH = 100
    return y + 48 + h + extraH
end function

sub loadSeason(idx as Integer)
    d = m.det
    if d = invalid or d.seasons = invalid or idx >= d.seasons.count() then return
    m.seasonIdx = idx
    for each z in m.zones
        if z.kind = "chips" then z.col = idx
    end for
    s = d.seasons[idx]
    startRequest(serverUrl(m.top.server) + "/api/title/episodes?season=" + txt(s.id), "GET", "", "onEpisodes")
end sub

sub onEpisodes(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.episodes = invalid then return
    sec = m.top.findNode("sections")
    old = sec.findNode("sec_eps")
    if old <> invalid then sec.removeChild(old)
    newZones = []
    for each z in m.zones
        if z.id <> "eps" then newZones.push(z)
    end for
    m.zones = newZones
    addRow(sec, "eps", "Episodes", data.episodes, "episode", m.epsY)
    ' keep the zones in screen order
    m.zones.sortBy("y")
    refocus(false)
end sub

' ---------------------------------------------------------------- focus

sub refocus(animate as Boolean)
    if m.zones.count() = 0 then return
    if m.z >= m.zones.count() then m.z = m.zones.count() - 1
    for zi = 0 to m.zones.count() - 1
        z = m.zones[zi]
        if z.kind = "buttons"
            for i = 0 to m.btns.count() - 1
                uiButtonFocus(m.btns[i], zi = m.z and i = z.col)
            end for
        else if z.kind = "chips"
            for i = 0 to z.btns.count() - 1
                uiButtonFocus(z.btns[i], (zi = m.z and i = z.col) or (zi <> m.z and i = m.seasonIdx))
            end for
        else
            for i = 0 to z.tiles.count() - 1
                uiFocus(z.tiles[i], zi = m.z and i = z.col, 1.06)
            end for
        end if
    end for
    z = m.zones[m.z]
    y = 0
    deck = 0.0
    if z.kind <> "buttons"
        y = 300 - z.y
        deck = 0.6
    end if
    if animate
        animTo(m.page, "translation", [0, y], "smooth")
        animTo(m.top.findNode("deck"), "opacity", deck, "smooth")
    else
        m.page.translation = [0, y]
        m.top.findNode("deck").opacity = deck
    end if
    if z.kind = "row" then scrollRow(z, animate)
end sub

sub scrollRow(z as Object, animate as Boolean)
    x = z.xs[z.col]
    target = z.offset
    if x + target > 1120 - z.w then target = 1120 - z.w - x
    if x + target < 0 then target = -x
    if target = z.offset then return
    z.offset = target
    if animate
        animTo(z.cardsGroup, "translation", [target, 0], "smooth")
    else
        z.cardsGroup.translation = [target, 0]
    end if
end sub

' ---------------------------------------------------------------- doing things

sub tell(a as Object)
    m.top.action = a
end sub

function baseItem() as Object
    s = src()
    it = { title: s.title, kind: s.kind, year: txt(s.year), poster: txt(s.poster), backdrop: txt(s.backdrop) }
    if s.ids <> invalid
        if s.ids.tvmaze <> invalid then it.tvmaze = s.ids.tvmaze
        if s.ids.anilist <> invalid then it.anilist = s.ids.anilist
    else
        if m.item.tvmaze <> invalid then it.tvmaze = m.item.tvmaze
        if m.item.anilist <> invalid then it.anilist = m.item.anilist
    end if
    if isTrue(s.anime) then it.anime = true
    return it
end function

sub play(provider as String, episode as Dynamic)
    body = { "item": baseItem() }
    if provider <> "" then body["provider"] = provider
    if episode <> invalid then body["episode"] = episode
    where = ""
    if provider <> "" then where = " on " + provider
    tell({ kind: "toast", text: "Opening " + src().title + where })
    startRequest(serverUrl(m.top.server) + "/api/title/play", "POST", FormatJson(body), "onPlayed")
end sub

sub onPlayed(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok then tell({ kind: "toast", text: "Couldn't open that. Is the Hub server running?" })
end sub

sub toggleSave()
    s = src()
    if m.saved
        startRequest(serverUrl(m.top.server) + "/api/shows/remove-title", "POST", FormatJson({ "title": s.title, "kind": s.kind }), "onSaved")
        m.saved = false
        tell({ kind: "toast", text: "Removed from My Shows" })
    else
        body = baseItem()
        if s.genres <> invalid then body.genres = s.genres
        if txt(s.network) <> "" then body.network = s.network
        startRequest(serverUrl(m.top.server) + "/api/shows/add", "POST", FormatJson(body), "onSaved")
        m.saved = true
        tell({ kind: "toast", text: "Saved to My Shows. Picks will lean this way." })
    end if
    buildButtons()
    refocus(false)
end sub

sub onSaved(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok
        m.saved = not m.saved
        buildButtons()
        refocus(false)
        tell({ kind: "toast", text: "Couldn't save that" })
    end if
end sub

sub activate()
    z = m.zones[m.z]
    if z.kind = "buttons"
        b = m.btns[z.col]
        if b.id = "play"
            play(txt(b.provider), invalid)
        else if b.id = "save"
            toggleSave()
        end if
    else if z.kind = "chips"
        loadSeason(z.col)
    else if z.id = "eps"
        ep = z.items[z.col]
        if txt(ep.contentId) <> ""
            play(txt(ep.provider), { contentId: ep.contentId, provider: ep.provider })
        else
            play("", invalid)
        end if
    else if z.id = "similar"
        it = z.items[z.col]
        if it.kind = invalid then it.kind = src().kind
        m.zones = []
        m.z = 0
        m.top.params = { item: it, accent: m.top.params.accent }
    end if
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if m.zones.count() = 0 then return key <> "back"
    z = m.zones[m.z]
    if key = "back"
        m.top.done = true
        return true
    else if key = "up"
        if m.z > 0
            m.z = m.z - 1
            refocus(true)
        end if
    else if key = "down"
        if m.z < m.zones.count() - 1
            m.z = m.z + 1
            refocus(true)
        end if
    else if key = "left"
        if z.col > 0
            z.col = z.col - 1
            refocus(true)
        end if
    else if key = "right"
        if z.col < z.count - 1
            z.col = z.col + 1
            refocus(true)
        end if
    else if key = "OK"
        activate()
    else if key = "play"
        play("", invalid)
    else
        return false
    end if
    return true
end function
