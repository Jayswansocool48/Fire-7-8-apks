sub init()
    m.top.findNode("title").font = fontFile("semibold", 36)
    m.top.findNode("sub").font = fontFile("regular", 20)
    m.top.findNode("prevName").font = fontFile("medium", 20)
    m.top.findNode("nextName").font = fontFile("medium", 20)
    m.top.findNode("hint").font = fontFile("regular", 17)
    m.top.findNode("shuffleTitle").font = fontFile("semibold", 30)
    m.top.findNode("kicker").font = fontFile("semibold", 18)
    m.top.findNode("pickTitle").font = fontFile("semibold", 52)
    m.top.findNode("pickMeta").font = fontFile("regular", 22)
    m.fCardTitle = fontFile("semibold", 30)
    m.fCardSub = fontFile("regular", 18)
    m.fButton = fontFile("semibold", 20)

    m.moods = []
    m.shows = {}
    m.index = 0
    m.stage = "choose"
    m.dir = 1
    m.pool = []
    m.spins = 0
    m.pick = invalid
    m.buttons = []
    m.button = 0
    m.busy = false

    m.outAnim = m.top.findNode("outAnim")
    m.outAnim.observeField("state", "onOutDone")
    m.timer = m.top.findNode("shuffleTimer")
    m.timer.observeField("fire", "onSpin")
    m.top.observeField("params", "onParams")
    m.top.findNode("auraAnim").control = "start"
    onParams()
end sub

sub onParams()
    p = m.top.params
    if p = invalid or p.moods = invalid then return
    m.top.findNode("bgA").uri = p.wallpaper
    m.top.findNode("aura").blendColor = rokuColor(p.accent, "FF")
    m.moods = p.moods
    m.shows = {}
    if p.shows <> invalid
        for each s in p.shows
            m.shows[s.id] = s
        end for
    end if
    if m.moods.count() = 0
        m.top.findNode("sub").text = "Add your favorite shows in the Hub Remote app (Hub tab, My Shows) and give them moods. Then I'll pick for you."
        m.top.findNode("sub").translation = [0, 300]
        m.top.findNode("stack").visible = false
        m.top.findNode("hint").text = "Back to Hub"
        return
    end if
    m.index = 0
    renderStack()
    m.top.findNode("inAnim").control = "start"
end sub

function posterOf(id as String) as String
    s = m.shows[id]
    if s = invalid then return ""
    return thumbUrl(m.top.server, s.poster)
end function

' A mood card: a 2x2 collage of posters with the mood's name.
sub fillCard(g as Object, mood as Object, withText as Boolean)
    g.removeChildrenIndex(g.getChildCount(), 0)
    shadow = g.createChild("Poster")
    shadow.uri = "pkg:/images/glow.png"
    shadow.width = 420
    shadow.height = 520
    shadow.translation = [-60, -44]
    shadow.blendColor = "0x000000FF"
    shadow.opacity = 0.6
    face = g.createChild("Poster")
    face.uri = "pkg:/images/card.9.png"
    face.width = 300
    face.height = 400
    face.blendColor = "0x1A1F3CFF"
    mask = g.createChild("MaskGroup")
    mask.maskUri = "pkg:/images/mask_card.png"
    mask.maskSize = [300, 400]
    ids = mood.ids
    for i = 0 to 3
        if ids.count() > 0
            id = ids[i mod ids.count()]
            p = mask.createChild("Poster")
            p.uri = posterOf(id)
            p.width = 150
            p.height = 200
            p.loadWidth = 150
            p.loadHeight = 200
            p.loadDisplayMode = "scaleToFill"
            p.translation = [(i mod 2) * 150, Int(i / 2) * 200]
        end if
    end for
    if withText
        sh = mask.createChild("Poster")
        sh.uri = "pkg:/images/shade.png"
        sh.width = 300
        sh.height = 260
        sh.translation = [0, 140]
        t = g.createChild("Label")
        t.text = mood.name
        t.font = m.fCardTitle
        t.color = "0xFFFFFFFF"
        t.width = 260
        t.horizAlign = "center"
        t.translation = [20, 318]
        c = g.createChild("Label")
        n = mood["count"]
        c.text = n.toStr() + " titles"
        if n = 1 then c.text = "1 title"
        c.font = m.fCardSub
        c.color = "0xFFFFFFB0"
        c.width = 260
        c.horizAlign = "center"
        c.translation = [20, 358]
    end if
end sub

sub renderStack()
    n = m.moods.count()
    mood = m.moods[m.index]
    fillCard(m.top.findNode("front"), mood, true)
    b1 = m.top.findNode("back1")
    b2 = m.top.findNode("back2")
    b1.visible = n > 1
    b2.visible = n > 2
    if n > 1 then fillCard(b1, m.moods[(m.index + 1) mod n], false)
    if n > 2 then fillCard(b2, m.moods[(m.index + 2) mod n], false)
    prev = ""
    nxt = ""
    if n > 1
        prev = Chr(8249) + "  " + m.moods[(m.index - 1 + n) mod n].name
        nxt = m.moods[(m.index + 1) mod n].name + "  " + Chr(8250)
    end if
    m.top.findNode("prevName").text = prev
    m.top.findNode("nextName").text = nxt
end sub

sub flip(delta as Integer)
    if m.moods.count() < 2 or m.busy then return
    m.busy = true
    m.dir = delta
    move = m.top.findNode("outMove")
    rot = m.top.findNode("outRot")
    if delta > 0
        move.keyValue = [[0, 0], [-480, 40]]
        rot.keyValue = [0.0, 0.35]
    else
        move.keyValue = [[0, 0], [480, 40]]
        rot.keyValue = [0.0, -0.35]
    end if
    m.outAnim.control = "start"
end sub

sub onOutDone()
    if m.outAnim.state <> "stopped" or not m.busy then return
    n = m.moods.count()
    m.index = (m.index + m.dir + n) mod n
    front = m.top.findNode("front")
    front.rotation = 0
    renderStack()
    m.top.findNode("inAnim").control = "start"
    m.busy = false
end sub

' ---------- shuffle ----------

sub startShuffle()
    mood = m.moods[m.index]
    if mood.ids.count() = 0 then return
    m.pool = mood.ids
    m.stage = "shuffle"
    m.top.findNode("choose").visible = false
    m.top.findNode("reveal").visible = false
    m.top.findNode("shuffle").visible = true
    m.top.findNode("shuffleTitle").text = "Shuffling " + mood.name + "..."
    m.spins = 0
    m.pick = m.shows[m.pool[Rnd(m.pool.count()) - 1]]
    m.timer.duration = 0.06
    onSpin()
end sub

' Flips through posters, slowing down like a slot machine, then lands on the pick.
sub onSpin()
    m.spins = m.spins + 1
    total = 18
    if m.spins >= total
        m.top.findNode("slotPoster").uri = thumbUrl(m.top.server, m.pick.poster)
        m.top.findNode("bumpAnim").control = "start"
        showReveal()
        return
    end if
    id = m.pool[Rnd(m.pool.count()) - 1]
    m.top.findNode("slotPoster").uri = posterOf(id)
    b = m.top.findNode("bumpAnim")
    b.control = "stop"
    b.control = "start"
    m.timer.duration = 0.06 + (m.spins * m.spins) * 0.0011
    m.timer.control = "start"
end sub

sub showReveal()
    s = m.pick
    m.stage = "reveal"
    m.top.findNode("shuffle").visible = false
    rv = m.top.findNode("reveal")
    rv.visible = true
    m.top.findNode("revealPoster").uri = thumbUrl(m.top.server, s.poster)
    m.top.findNode("revealGlow").blendColor = rokuColor(m.top.params.accent, "FF")
    bg = s.backdrop
    if bg = invalid or bg = "" then bg = s.poster
    m.top.findNode("bgB").uri = thumbUrl(m.top.server, bg)
    m.top.findNode("kicker").text = "Hub picked, from " + m.moods[m.index].name
    m.top.findNode("kicker").color = rokuColor(m.top.params.accent, "FF")
    t = m.top.findNode("pickTitle")
    t.text = s.title
    h = t.boundingRect().height
    meta = "TV show"
    if s.kind = "movie" then meta = "Movie"
    if s.year <> invalid and s.year <> "" then meta = s.year + "  " + meta
    if s.appName <> invalid and s.appName <> "" then meta = meta + "  on " + s.appName
    mt = m.top.findNode("pickMeta")
    mt.text = meta
    mt.translation = [492, 224 + h + 14]

    watch = "Watch now"
    if s.appName <> invalid and s.appName <> "" then watch = "Watch on " + s.appName
    m.buttons = [watch, "Pick again", "Change mood"]
    bar = m.top.findNode("buttons")
    bar.translation = [490, 224 + h + 76]
    bar.removeChildrenIndex(bar.getChildCount(), 0)
    x = 0
    for i = 0 to m.buttons.count() - 1
        g = bar.createChild("Group")
        lbl = CreateObject("roSGNode", "Label")
        lbl.text = m.buttons[i]
        lbl.font = m.fButton
        lbl.height = 56
        lbl.vertAlign = "center"
        w = lbl.boundingRect().width + 56
        bg2 = g.createChild("Poster")
        bg2.uri = "pkg:/images/pill.9.png"
        bg2.width = w
        bg2.height = 56
        lbl.width = w
        lbl.horizAlign = "center"
        g.appendChild(lbl)
        g.translation = [x, 0]
        x = x + w + 16
    end for
    m.button = 0
    focusButton()
    m.top.findNode("revealAnim").control = "start"
end sub

sub focusButton()
    bar = m.top.findNode("buttons")
    for i = 0 to bar.getChildCount() - 1
        g = bar.getChild(i)
        bg = g.getChild(0)
        lbl = g.getChild(1)
        if i = m.button
            bg.blendColor = "0xFFFFFFFF"
            lbl.color = "0x0B0F22FF"
        else
            bg.blendColor = "0xFFFFFF26"
            lbl.color = "0xFFFFFFFF"
        end if
    end for
end sub

sub pressButton()
    if m.button = 0
        startRequest(serverUrl(m.top.server) + "/api/shows/play", "POST", FormatJson({ "id": m.pick.id }), "onPlayed")
        m.top.findNode("kicker").text = "Opening..."
    else if m.button = 1
        m.top.findNode("bgB").opacity = 0
        startShuffle()
    else
        backToMoods()
    end if
end sub

sub onPlayed(msg as Object)
    releaseTask(msg.getRoSGNode())
    if msg.getData().ok
        m.top.done = true
    else
        m.top.findNode("kicker").text = "Couldn't open it. Is the Hub server running?"
    end if
end sub

sub backToMoods()
    m.timer.control = "stop"
    m.stage = "choose"
    m.top.findNode("reveal").visible = false
    m.top.findNode("reveal").opacity = 0
    m.top.findNode("shuffle").visible = false
    m.top.findNode("bgB").opacity = 0
    m.top.findNode("choose").visible = true
    m.top.findNode("inAnim").control = "start"
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if m.stage = "choose"
        if key = "left"
            flip(-1)
        else if key = "right"
            flip(1)
        else if key = "OK" or key = "play"
            if m.moods.count() > 0 and not m.busy then startShuffle()
        else
            return false
        end if
        return true
    else if m.stage = "reveal"
        if key = "left"
            if m.button > 0 then m.button = m.button - 1
            focusButton()
        else if key = "right"
            if m.button < m.buttons.count() - 1 then m.button = m.button + 1
            focusButton()
        else if key = "OK"
            pressButton()
        else if key = "back"
            backToMoods()
        else
            return false
        end if
        return true
    else if m.stage = "shuffle"
        if key = "back"
            backToMoods()
        end if
        return true
    end if
    return false
end function
