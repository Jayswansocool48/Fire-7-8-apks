' Pieces both home looks share: the data helpers, Today widgets, details lookups
' (where a title streams on your TV) and the backdrop crossfade.
' The home that includes this provides detailReady(item) and keeps m.st, m.d, m.hub, m.server.

function listOf(v as Dynamic) as Object
    if v = invalid then return []
    return v
end function

function isAnime(it as Object) as Boolean
    if isTrue(it.anime) then return true
    if it.genres <> invalid
        for each g in it.genres
            if g = "Anime" then return true
        end for
    end if
    return false
end function

' Channels (most recently used first) and Hub's own tools.
function appItems(limit as Integer, tools as Boolean) as Object
    out = []
    seen = {}
    chans = listOf(m.d.channels)
    for each id in listOf(m.st.recentApps)
        for each c in chans
            if c.id = id and not seen.doesExist(c.id)
                out.push({ kind: "channel", id: c.id, title: c.name })
                seen[c.id] = true
            end if
        end for
    end for
    for each c in chans
        if not seen.doesExist(c.id)
            out.push({ kind: "channel", id: c.id, title: c.name })
            seen[c.id] = true
        end if
    end for
    if limit > 0 and out.count() > limit
        cut = []
        for i = 0 to limit - 1
            cut.push(out[i])
        end for
        out = cut
    end if
    if tools
        for each t in m.d.tools
            out.push({ kind: "tool", id: t.id, title: t.name, icon: t.icon })
        end for
    end if
    return out
end function

' Widget cards: weather, what's next, the latest note, what you watched last, and a
' sleep timer at night.
function todayItems() as Object
    out = []
    w = m.st.widgets
    if w = invalid then w = {}
    if w.weather <> invalid then out.push({ kind: "weather", title: "Weather", w: w.weather })
    hist = listOf(m.st.history)
    if hist.count() > 0 then out.push({ kind: "last", title: "Pick up where you left off", item: hist[0] })
    if m.d.mode = "night" then out.push({ kind: "sleep", title: "Sleep timer" })
    ag = listOf(w.agenda)
    if ag.count() > 0 then out.push({ kind: "next", title: "Up next", e: ag[0] })
    notes = listOf(w.notes)
    if notes.count() > 0 then out.push({ kind: "noteCard", title: "Note", n: notes[0] })
    out.push({ kind: "music", title: "Music" })
    out.push({ kind: "tool", id: "ask", title: "Ask Hub", icon: "pkg:/images/i_ask.png" })
    return out
end function

function todayTitle() as String
    if m.d.mode = "morning" then return "This morning"
    if m.d.mode = "night" then return "Tonight"
    return "Today"
end function

sub fillWidget(tile as Object, it as Object, w as Integer, h as Integer)
    k = it.kind
    accent = rokuColor(m.hub.accent, "FF")
    if k = "weather"
        ic = uiImage(tile, "pkg:/images/w_" + it.w.icon + ".png", 20, 20, 52, 52)
        ic.blendColor = pal("cream")
        uiLabel(tile, it.w.temp.toStr() + Chr(176), 84, 16, 170, fontFile("title", 40), pal("cream"))
        uiLabel(tile, it.w.text, 20, 86, w - 40, m.fSmallBold, pal("cream"))
        tip = ""
        if it.w.tip <> invalid then tip = it.w.tip
        t = uiLabel(tile, tip, 20, 112, w - 40, m.fSmall, "0xECEDF0B0")
        t.maxLines = 1
    else if k = "last"
        uiLabel(tile, "Pick up where you left off", 20, 18, w - 40, m.fSmallBold, accent)
        t = uiLabel(tile, it.item.title, 20, 46, w - 40, fontFile("title", 24), pal("cream"))
        t.wrap = true
        t.maxLines = 2
        a = "OK to open it again"
        if it.item.appName <> invalid and it.item.appName <> "" then a = "On " + it.item.appName + "  " + Chr(183) + "  " + agoOf(it.item.at)
        uiLabel(tile, a, 20, h - 36, w - 40, m.fSmall, "0xECEDF0B0")
    else if k = "sleep"
        ic = uiImage(tile, "pkg:/images/moon.png", 20, 20, 40, 40)
        ic.blendColor = pal("cream")
        uiLabel(tile, "Sleep timer", 20, 72, w - 40, fontFile("title", 24), pal("cream"))
        uiLabel(tile, "OK to turn the TV off in 30 min", 20, 108, w - 40, m.fSmall, "0xECEDF0B0")
    else if k = "next"
        uiLabel(tile, "Up next  " + Chr(183) + "  " + dayAndTime(it.e.start, isTrue(m.hub.clock24), isTrue(it.e.allDay)), 20, 18, w - 40, m.fSmallBold, accent)
        t = uiLabel(tile, it.e.title, 20, 46, w - 40, fontFile("title", 24), pal("cream"))
        t.wrap = true
        t.maxLines = 3
    else if k = "noteCard"
        n = it.n
        if txt(n.kind) = "drawing"
            p = uiImage(tile, absUrl(m.server, n.image), 16, 12, w - 32, h - 40)
            p.loadDisplayMode = "scaleToFit"
        else
            t = uiLabel(tile, n.text, 20, 18, w - 40, fontFile("semibold", 22), pal("cream"))
            t.wrap = true
            t.maxLines = 4
        end if
        uiLabel(tile, "Note  " + Chr(183) + "  " + agoOf(n.created), 20, h - 30, w - 40, m.fSmall, "0xECEDF0A0")
    else if k = "music"
        ic = uiImage(tile, "pkg:/images/i_music.png", 20, 20, 40, 40)
        ic.blendColor = pal("cream")
        uiLabel(tile, "Music", 20, 72, w - 40, fontFile("title", 24), pal("cream"))
        uiLabel(tile, "Play songs from your server", 20, 108, w - 40, m.fSmall, "0xECEDF0B0")
    else if k = "tool"
        ic = uiImage(tile, it.icon, 20, 20, 40, 40)
        ic.blendColor = pal("cream")
        uiLabel(tile, it.title, 20, 72, w - 40, fontFile("title", 24), pal("cream"))
        uiLabel(tile, "Ask about the weather, shows, anything", 20, 108, w - 40, m.fSmall, "0xECEDF0B0")
    else if k = "hint"
        uiLabel(tile, it.title, 20, 18, w - 40, m.fSmallBold, accent)
        t = uiLabel(tile, it.text, 20, 46, w - 40, m.fSmall, pal("cream"))
        t.wrap = true
        t.maxLines = 5
    end if
end sub

function detailFor(it as Object) as Dynamic
    if it = invalid or it.title = invalid then return invalid
    return m.detail[detailKey(it)]
end function

function detailKey(it as Object) as String
    k = "tv"
    if it.kind = "movie" then k = "movie"
    return k + ":" + LCase(it.title)
end function

function isTitle(it as Object) as Boolean
    if it = invalid then return false
    if it.kind = "movie" or it.kind = "tv" then return true
    return false
end function

sub requestDetail(it as Object)
    if not isTitle(it) or m.server = "" then return
    k = detailKey(it)
    if m.detail.doesExist(k) or m.pending.doesExist(k) then return
    m.pending[k] = true
    t = startRequest(serverUrl(m.server) + "/api/title" + titleQuery(it), "GET", "", "onDetail")
    t.addField("detailKey", "string", false)
    t.detailKey = k
end sub

sub onDetail(msg as Object)
    t = msg.getRoSGNode()
    k = t.detailKey
    releaseTask(t)
    m.pending.delete(k)
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.title = invalid
        m.detail[k] = {}
        return
    end if
    m.detail[k] = data.title
    cur = focusedItem()
    if cur <> invalid and isTitle(cur) and detailKey(cur) = k then detailReady(cur)
end sub

' "On your TV" chips for the services that have it; the rest in one line.
function drawAvail(parent as Object, it as Object, det as Dynamic) as Integer
    x = 0
    if det <> invalid and det.providers <> invalid and det.providers.count() > 0
        others = []
        for each p in det.providers
            if isTrue(p.installed) and x < 560
                label = p.appName
                if p.contentId <> invalid and p.contentId <> "" then label = Chr(9654) + "  " + label
                x = x + uiChip(parent, label, x, 0, m.fChip, pal("cream"), pal("ink")) + 10
            else if not isTrue(p.installed)
                others.push(p.name)
            end if
        end for
        if others.count() > 0 and x < 640
            more = "Also on " + joinText(others, ", ")
            if x = 0 then more = "Streams on " + joinText(others, ", ") + ". Not on your TV yet."
            l = uiLabel(parent, more, x + 4, 0, 760 - x, m.fChip, pal("mist"))
            l.height = 34
            l.vertAlign = "center"
            x = x + 10
        end if
    else if it.appName <> invalid and it.appName <> ""
        x = uiChip(parent, it.appName, 0, 0, m.fChip, pal("cream"), pal("ink"))
    end if
    return x
end function

function itemMeta(it as Object) as String
    k = it.kind
    if k = "channel" then return "Channel on your TV"
    if k = "tool" then return "Part of Hub"
    if k = "video" or it.thumb <> invalid then return txt(it.channel)
    if k = "weather" then return txt(it.w.text)
    if k = "next" then return dayAndTime(it.e.start, isTrue(m.hub.clock24), isTrue(it.e.allDay))
    if it.start <> invalid then return dayAndTime(it.start, isTrue(m.hub.clock24), isTrue(it.allDay))
    if it.cover <> invalid and it.episode <> invalid then return "Episode " + it.episode.toStr() + " " + untilText(it.airingAt - CreateObject("roDateTime").asSeconds())
    return ""
end function

function itemSummary(it as Object) as String
    k = it.kind
    if k = "channel" then return "Press OK to open " + it.title + "."
    if k = "tool" and it.id = "ask" then return "Ask about the weather, your calendar, or what to watch tonight."
    if k = "tool" and it.id = "browser" then return "A web browser with ads blocked, controlled from your tablet."
    if k = "tool" and it.id = "library" then return "Movies, music and photos from your server."
    if k = "tool" and it.id = "settings" then return "Server address, look, profiles, privacy and removing Hub."
    if k = "weather" and it.w.tip <> invalid then return it.w.tip
    if k = "last" then return "Opens " + it.item.title + " right where the app keeps your place."
    if k = "sleep" then return "Turns the TV off in 30 minutes. Set other times from Hub Remote."
    if k = "music" then return "Your music folders on the server, played here on the TV."
    if k = "noteCard" then return "Sent from Hub Remote. Draw or type new ones on the tablet."
    if it.cover <> invalid then return "Opens Crunchyroll."
    return ""
end function

sub setBackdrop(url as String)
    if url = m.bgUrl then return
    m.bgUrl = url
    front = m.top.findNode(m.bgFront)
    if url = ""
        animTo(front, "opacity", 0.0, "gentle")
        return
    end if
    back = "bgB"
    if m.bgFront = "bgB" then back = "bgA"
    b = m.top.findNode(back)
    u = thumbUrl(m.server, url)
    if b.uri = u and b.loadStatus = "ready"
        swapBackdrop(back)
        return
    end if
    b.opacity = 0
    b.uri = u
end sub

' The new picture fades in over the old one. If it can't load, the old one fades away so a
' stale backdrop never sits behind a different title.
sub onBgLoaded()
    want = thumbUrl(m.server, m.bgUrl)
    for each id in ["bgA", "bgB"]
        p = m.top.findNode(id)
        if id <> m.bgFront and p.uri = want
            if p.loadStatus = "ready"
                swapBackdrop(id)
            else if p.loadStatus = "failed"
                animTo(m.top.findNode(m.bgFront), "opacity", 0.0, "gentle")
            end if
        end if
    end for
end sub

sub swapBackdrop(id as String)
    animTo(m.top.findNode(id), "opacity", 1.0, "gentle")
    animTo(m.top.findNode(m.bgFront), "opacity", 0.0, "gentle")
    m.bgFront = id
end sub

sub act(a as Object)
    m.top.action = a
end sub

sub springPressTile(tile as Object, s as Float)
    if m.pressAnim = invalid
        m.pressAnim = m.top.createChild("Animation")
        m.pressInterp = m.pressAnim.createChild("Vector2DFieldInterpolator")
    end if
    m.pressAnim.control = "stop"
    springPress(m.pressAnim, m.pressInterp, s)
    m.pressInterp.fieldToInterp = tile.id + ".scale"
    m.pressAnim.control = "start"
end sub
