' Hub recommends a show or movie. Picks come from the Hub server (/api/recommend), which
' leans on the shows you saved and tells you which one each pick follows from.

sub init()
    m.top.findNode("kicker").font = fontFile("semibold", 18)
    m.top.findNode("title").font = fontFile("title", 52)
    m.top.findNode("meta").font = fontFile("medium", 20)
    m.top.findNode("reason").font = fontFile("semibold", 20)
    m.top.findNode("summary").font = fontFile("regular", 20)
    m.top.findNode("empty").font = fontFile("title", 30)
    m.top.findNode("status").font = fontFile("medium", 18)
    m.top.findNode("hint").font = fontFile("regular", 16)
    m.fButton = fontFile("semibold", 19)
    m.fChip = fontFile("medium", 17)

    m.accent = "#D9894A"
    m.kinds = [{ id: "any", name: "Anything" }, { id: "tv", name: "Shows" }, { id: "movie", name: "Movies" }]
    m.kind = 0
    m.items = []
    m.index = -1
    m.loading = false
    m.zone = "buttons"   ' or "chips"
    m.button = 0
    m.buttons = []
    m.undo = invalid
    m.saved = {}
    m.bgFront = "bgA"

    m.contentAnim = m.top.findNode("contentAnim")
    m.bgAnim = m.top.findNode("bgAnim")
    m.bgAnim.observeField("state", "onBgDone")
    driftLoop(m.top.findNode("skelAnim"), m.top.findNode("skelInterp"), 0.45, 0.9, 1.4)
    m.statusTimer = m.top.findNode("statusTimer")
    m.statusTimer.observeField("fire", "onStatusTimer")
    m.top.observeField("params", "onParams")
    renderChips()
    onParams()
end sub

sub onParams()
    p = m.top.params
    if p = invalid then return
    if p.accent <> invalid then m.accent = p.accent
    m.top.findNode("kicker").color = rokuColor(m.accent, "FF")
    m.top.findNode("reason").color = rokuColor(m.accent, "FF")
    if m.index >= 0 then return
    if p.pick <> invalid and p.pick.title <> invalid
        m.items = [p.pick]
        show(0, false)
    else
        setSkeleton(true)
    end if
    fetchMore()
end sub

' =====================================================================  data

sub fetchMore()
    if m.loading or m.top.server = "" then return
    m.loading = true
    url = serverUrl(m.top.server) + "/api/recommend?n=8&kind=" + m.kinds[m.kind].id
    startRequest(url, "GET", "", "onPicks")
end sub

sub onPicks(msg as Object)
    releaseTask(msg.getRoSGNode())
    m.loading = false
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.items = invalid
        if m.items.count() = 0 then showEmpty("Hub couldn't get picks from the server. Check that it's running, then try again.")
        return
    end if
    for each it in data.items
        dup = false
        for each have in m.items
            if have.key = it.key then dup = true
        end for
        if not dup then m.items.push(it)
    end for
    if m.index < 0
        if m.items.count() > 0
            show(0, true)
        else
            showEmpty("Nothing new to recommend right now. Save a few shows in Hub Remote and Hub learns what you like.")
        end if
    end if
end sub

sub showEmpty(text as String)
    setSkeleton(false)
    m.top.findNode("content").opacity = 0
    e = m.top.findNode("empty")
    e.text = text
    e.visible = true
end sub

sub setSkeleton(isOn as Boolean)
    sk = m.top.findNode("skeleton")
    a = m.top.findNode("skelAnim")
    if isOn
        m.top.findNode("empty").visible = false
        sk.visible = true
        a.control = "start"
    else
        a.control = "stop"
        sk.visible = false
    end if
end sub

' =====================================================================  showing a pick

sub show(i as Integer, animate as Boolean)
    if i < 0 or i >= m.items.count() then return
    m.index = i
    it = m.items[i]
    setSkeleton(false)
    m.top.findNode("empty").visible = false

    kindName = "Show"
    if it.kind = "movie" then kindName = "Movie"
    m.top.findNode("kicker").text = "Hub recommends"
    title = m.top.findNode("title")
    title.text = it.title
    y = 178 + title.boundingRect().height + 14
    parts = []
    if it.year <> invalid and it.year <> "" then parts.push(it.year)
    parts.push(kindName)
    if it.genres <> invalid and it.genres.count() > 0
        g = it.genres[0]
        if it.genres.count() > 1 then g = g + ", " + it.genres[1]
        parts.push(g)
    end if
    if it.appName <> invalid and it.appName <> "" then parts.push("on " + it.appName)
    meta = m.top.findNode("meta")
    meta.text = parts.join("  " + Chr(183) + "  ")
    meta.translation = [82, y]
    y = y + 36
    reason = m.top.findNode("reason")
    reason.text = it.reason
    reason.translation = [82, y]
    y = y + 44
    summary = m.top.findNode("summary")
    summary.text = it.summary
    summary.translation = [82, y]
    if it.summary <> invalid and it.summary <> "" then y = y + summary.boundingRect().height + 34
    buildButtons(it, y)

    poster = m.top.findNode("poster")
    poster.uri = thumbUrl(m.top.server, it.poster)
    m.top.findNode("posterMask").visible = it.poster <> invalid and it.poster <> ""
    setBackdrop(it)

    c = m.top.findNode("content")
    if animate
        m.contentAnim.control = "stop"
        springFade(m.contentAnim, m.top.findNode("contentFade"), 0.0, 1.0, "smooth")
        springTo(invalid, m.top.findNode("contentMove"), [40, 0], [0, 0], "smooth")
        m.contentAnim.control = "start"
    else
        c.opacity = 1
        c.translation = [0, 0]
    end if
    startRequest(serverUrl(m.top.server) + "/api/recommend/seen", "POST", FormatJson({ "key": it.key }), "onQuiet")
    if m.items.count() - i <= 2 then fetchMore()
end sub

' The backdrop crossfades. With no backdrop, the poster stretched full screen gives a soft wash.
sub setBackdrop(it as Object)
    uri = ""
    wash = false
    if it.backdrop <> invalid and it.backdrop <> ""
        uri = thumbUrl(m.top.server, it.backdrop)
    else if it.poster <> invalid and it.poster <> ""
        uri = thumbUrl(m.top.server, it.poster)
        wash = true
    end if
    backName = "bgB"
    if m.bgFront = "bgB" then backName = "bgA"
    back = m.top.findNode(backName)
    front = m.top.findNode(m.bgFront)
    if uri = ""
        front.opacity = 0
        return
    end if
    back.uri = uri
    target = 0.85
    if wash then target = 0.35
    m.bgAnim.control = "stop"
    springFade(m.bgAnim, m.top.findNode("bgInterp"), 0.0, target, "gentle")
    m.top.findNode("bgInterp").fieldToInterp = backName + ".opacity"
    back.opacity = 0
    m.bgAnim.control = "start"
    m.bgNext = backName
end sub

sub onBgDone()
    if m.bgAnim.state <> "stopped" or m.bgNext = invalid then return
    m.top.findNode(m.bgFront).opacity = 0
    m.bgFront = m.bgNext
    m.bgNext = invalid
end sub

' =====================================================================  buttons and chips

function makeButton(text as String, active as Boolean) as Object
    g = CreateObject("roSGNode", "Group")
    lbl = CreateObject("roSGNode", "Label")
    lbl.font = m.fButton
    lbl.text = text
    lbl.height = 52
    lbl.vertAlign = "center"
    w = lbl.boundingRect().width + 48
    bg = g.createChild("Poster")
    bg.uri = "pkg:/images/pill.9.png"
    bg.width = w
    bg.height = 52
    lbl.width = w
    lbl.horizAlign = "center"
    g.appendChild(lbl)
    g.scaleRotateCenter = [w / 2, 26]
    styleButton(g, active)
    return { node: g, w: w }
end function

sub styleButton(g as Object, active as Boolean)
    bg = g.getChild(0)
    lbl = g.getChild(1)
    if active
        bg.blendColor = pal("cream")
        lbl.color = pal("ink")
    else
        bg.blendColor = "0x2A251FE6"
        lbl.color = pal("cream")
    end if
end sub

sub buildButtons(it as Object, y as Integer)
    row = m.top.findNode("buttons")
    row.removeChildrenIndex(row.getChildCount(), 0)
    row.translation = [80, y]
    watch = "Find it on Roku"
    if it.appName <> invalid and it.appName <> "" then watch = "Watch on " + it.appName
    saveText = "Save to My Shows"
    if m.saved.doesExist(it.key) then saveText = "Saved"
    m.buttons = [{ id: "watch", text: watch }, { id: "next", text: "Another pick" }, { id: "save", text: saveText }, { id: "pass", text: "Not for me" }]
    if m.button >= m.buttons.count() then m.button = 0
    x = 0
    for i = 0 to m.buttons.count() - 1
        b = makeButton(m.buttons[i].text, m.zone = "buttons" and i = m.button)
        b.node.id = "btn" + i.toStr()
        b.node.translation = [x, 0]
        row.appendChild(b.node)
        m.buttons[i].node = b.node
        x = x + b.w + 14
    end for
end sub

sub focusButton(i as Integer)
    if i < 0 or i >= m.buttons.count() then return
    m.button = i
    for j = 0 to m.buttons.count() - 1
        styleButton(m.buttons[j].node, m.zone = "buttons" and j = i)
    end for
end sub

sub renderChips()
    row = m.top.findNode("chips")
    row.removeChildrenIndex(row.getChildCount(), 0)
    x = 0
    for i = 0 to m.kinds.count() - 1
        g = CreateObject("roSGNode", "Group")
        lbl = CreateObject("roSGNode", "Label")
        lbl.font = m.fChip
        lbl.text = m.kinds[i].name
        lbl.height = 40
        lbl.vertAlign = "center"
        w = lbl.boundingRect().width + 36
        bg = g.createChild("Poster")
        bg.uri = "pkg:/images/pill.9.png"
        bg.width = w
        bg.height = 40
        lbl.width = w
        lbl.horizAlign = "center"
        g.appendChild(lbl)
        if i = m.kind and m.zone = "chips"
            bg.blendColor = pal("cream")
            lbl.color = pal("ink")
        else if i = m.kind
            bg.blendColor = rokuColor(m.accent, "FF")
            lbl.color = pal("ink")
        else
            bg.blendColor = "0x2A251FCC"
            lbl.color = pal("cream")
        end if
        g.translation = [x, 0]
        row.appendChild(g)
        x = x + w + 10
    end for
end sub

sub setKind(i as Integer)
    if i < 0 or i >= m.kinds.count() or i = m.kind then return
    m.kind = i
    renderChips()
    m.items = []
    m.index = -1
    m.top.findNode("content").opacity = 0
    setSkeleton(true)
    fetchMore()
end sub

' =====================================================================  actions

sub pressButton()
    if m.index < 0 or m.buttons.count() = 0 then return
    b = m.buttons[m.button]
    springPress(m.top.findNode("pressAnim"), m.top.findNode("pressInterp"), 1.0)
    m.top.findNode("pressInterp").fieldToInterp = b.node.id + ".scale"
    it = m.items[m.index]
    if b.id = "watch"
        say("Opening " + it.title)
        body = { "title": it.title, "kind": it.kind, "app": it.app, "appName": it.appName }
        startRequest(serverUrl(m.top.server) + "/api/recommend/watch", "POST", FormatJson(body), "onWatch")
    else if b.id = "next"
        nextPick(1)
    else if b.id = "save"
        if m.saved.doesExist(it.key) then return
        m.saved[it.key] = true
        b.node.getChild(1).text = "Saved"
        say("Saved to My Shows. Picks will lean this way.")
        body = { "title": it.title, "kind": it.kind, "year": it.year, "poster": it.poster, "backdrop": it.backdrop, "network": it.network, "genres": it.genres, "app": it.app, "appName": it.appName }
        startRequest(serverUrl(m.top.server) + "/api/shows/add", "POST", FormatJson(body), "onSaved")
    else if b.id = "pass"
        m.undo = { item: it, index: m.index }
        startRequest(serverUrl(m.top.server) + "/api/recommend/pass", "POST", FormatJson({ "key": it.key }), "onQuiet")
        m.items.delete(m.index)
        say("Hidden. Press Replay to undo.")
        if m.items.count() = 0
            m.index = -1
            m.top.findNode("content").opacity = 0
            setSkeleton(true)
            fetchMore()
        else
            idx = m.index
            if idx >= m.items.count() then idx = m.items.count() - 1
            show(idx, true)
        end if
    end if
end sub

sub nextPick(dirn as Integer)
    n = m.index + dirn
    if n < 0 then return
    if n >= m.items.count()
        setSkeleton(true)
        m.top.findNode("content").opacity = 0
        m.index = -1
        fetchMore()
        return
    end if
    show(n, true)
end sub

sub undoPass()
    if m.undo = invalid then return
    u = m.undo
    m.undo = invalid
    startRequest(serverUrl(m.top.server) + "/api/recommend/pass", "POST", FormatJson({ "key": u.item.key, "undo": true }), "onQuiet")
    idx = u.index
    if idx > m.items.count() then idx = m.items.count()
    out = []
    for j = 0 to m.items.count() - 1
        if j = idx then out.push(u.item)
        out.push(m.items[j])
    end for
    if idx >= m.items.count() then out.push(u.item)
    m.items = out
    say("Back in your picks.")
    show(idx, true)
end sub

sub onWatch(msg as Object)
    releaseTask(msg.getRoSGNode())
    if msg.getData().ok
        m.top.done = true
    else
        say("Couldn't open that. Check the Hub server and the TV.")
    end if
end sub

sub onSaved(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok then say("Couldn't save that right now.")
end sub

sub onQuiet(msg as Object)
    releaseTask(msg.getRoSGNode())
end sub

' A short line at the bottom that fades in, then out.
sub say(text as String)
    st = m.top.findNode("status")
    st.text = text
    m.top.findNode("statusOut").control = "stop"
    a = m.top.findNode("statusIn")
    springFade(a, m.top.findNode("statusInFade"), st.opacity, 1.0, "smooth")
    a.control = "start"
    m.statusTimer.control = "stop"
    m.statusTimer.control = "start"
end sub

sub onStatusTimer()
    m.undo = invalid
    st = m.top.findNode("status")
    a = m.top.findNode("statusOut")
    springFade(a, m.top.findNode("statusOutFade"), st.opacity, 0.0, "gentle")
    a.control = "start"
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "back" then return false
    if key = "fastforward"
        nextPick(1)
    else if key = "rewind"
        nextPick(-1)
    else if key = "replay"
        undoPass()
    else if m.zone = "chips"
        if key = "left"
            setKind(m.kind - 1)
        else if key = "right"
            setKind(m.kind + 1)
        else if key = "down" or key = "OK"
            m.zone = "buttons"
            renderChips()
            focusButton(m.button)
        end if
    else
        if key = "left"
            focusButton(m.button - 1)
        else if key = "right"
            focusButton(m.button + 1)
        else if key = "up"
            m.zone = "chips"
            renderChips()
            focusButton(m.button)
        else if key = "OK"
            pressButton()
        else if key = "play"
            nextPick(1)
        end if
    end if
    return true
end function
