' Pick a profile; Hub switches your shows, history, recommendations and look to it.
' The last circle adds a new profile with the on-screen keyboard.

sub init()
    m.top.findNode("heading").font = fontFile("title", 46)
    m.top.findNode("hint").font = fontFile("regular", 17)
    m.col = 0
    m.tiles = []
    m.people = []
    m.dialog = invalid
end sub

sub onParams()
    p = m.top.params
    if p = invalid then return
    m.people = []
    if p.profiles <> invalid then m.people = p.profiles
    build()
end sub

sub build()
    g = m.top.findNode("people")
    g.removeChildrenIndex(g.getChildCount(), 0)
    m.tiles = []
    n = m.people.count() + 1
    size = 150
    gap = 56
    total = n * size + (n - 1) * gap
    x = Int((1280 - total) / 2)
    m.col = 0
    for i = 0 to n - 1
        t = g.createChild("Group")
        t.id = "pf" + i.toStr()
        t.translation = [x, 0]
        t.scaleRotateCenter = [size / 2, size / 2]
        ring = uiPatch(t, "pkg:/images/circle.png", -8, -8, size + 16, size + 16, pal("cream"))
        ring.opacity = 0
        if i < m.people.count()
            p = m.people[i]
            uiAvatar(t, p.name, p.color, 0, 0, size, fontFile("title", 64))
            l = uiLabel(t, p.name, -40, size + 22, size + 80, fontFile("medium", 22), pal("cream"))
            l.horizAlign = "center"
            if isTrue(p.current) then m.col = i
        else
            uiPatch(t, "pkg:/images/circle.png", 0, 0, size, size, pal("raise"))
            ic = uiImage(t, "pkg:/images/i_plus.png", 45, 45, 60, 60)
            ic.blendColor = pal("cream")
            l = uiLabel(t, "Add profile", -40, size + 22, size + 80, fontFile("medium", 22), pal("mist"))
            l.horizAlign = "center"
        end if
        m.tiles.push(t)
        x = x + size + gap
    end for
    paint()
end sub

sub paint()
    for i = 0 to m.tiles.count() - 1
        t = m.tiles[i]
        if i = m.col
            animTo(t, "scale", [1.1, 1.1], "snappy")
            animTo(t.getChild(0), "opacity", 1.0, "snappy")
        else
            animTo(t, "scale", [1.0, 1.0], "snappy")
            animTo(t.getChild(0), "opacity", 0.0, "snappy")
        end if
    end for
end sub

sub choose()
    if m.col < m.people.count()
        p = m.people[m.col]
        if isTrue(p.current)
            m.top.done = true
            return
        end if
        m.top.action = { kind: "toast", text: "Hi, " + p.name }
        startRequest(serverUrl(m.top.server) + "/api/profiles/switch", "POST", FormatJson({ "id": p.id }), "onSwitched")
    else
        dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
        dlg.title = "New profile"
        dlg.message = ["Their name"]
        dlg.buttons = ["Add", "Cancel"]
        dlg.observeField("buttonSelected", "onAddButton")
        dlg.observeField("wasClosed", "onAddClosed")
        m.dialog = dlg
        m.top.getScene().dialog = dlg
    end if
end sub

sub onSwitched(msg as Object)
    releaseTask(msg.getRoSGNode())
    if not msg.getData().ok then m.top.action = { kind: "toast", text: "Couldn't switch profiles" }
    m.top.done = true
end sub

sub onAddButton()
    idx = m.dialog.buttonSelected
    name = m.dialog.text.trim()
    m.dialog.close = true
    m.dialog = invalid
    m.top.setFocus(true)
    if idx = 0 and name <> ""
        startRequest(serverUrl(m.top.server) + "/api/profiles/add", "POST", FormatJson({ "name": name }), "onAdded")
    end if
end sub

sub onAddClosed()
    m.dialog = invalid
    m.top.setFocus(true)
end sub

sub onAdded(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.profiles = invalid
        m.top.action = { kind: "toast", text: "Couldn't add that profile" }
        return
    end if
    m.people = data.profiles
    build()
    m.col = m.people.count() - 1
    paint()
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "left"
        if m.col > 0
            m.col = m.col - 1
            paint()
        end if
    else if key = "right"
        if m.col < m.tiles.count() - 1
            m.col = m.col + 1
            paint()
        end if
    else if key = "OK"
        choose()
    else if key = "back"
        m.top.done = true
    else
        return false
    end if
    return true
end function
