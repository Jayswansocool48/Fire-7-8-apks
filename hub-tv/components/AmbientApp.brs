sub init()
    m.front = m.top.findNode("slideB")
    m.back = m.top.findNode("slideA")
    m.top.findNode("slideA").observeField("loadStatus", "onLoad")
    m.top.findNode("slideB").observeField("loadStatus", "onLoad")
    m.top.findNode("clock").font = makeFont(true, 88)
    m.top.findNode("date").font = makeFont(false, 26)
    m.top.findNode("weather").font = makeFont(false, 24)
    m.photos = []
    m.pos = 0
    m.flip = 0
    m.started = false
    t = m.top.findNode("nextTimer")
    t.observeField("fire", "nextPhoto")
    c = m.top.findNode("clockTimer")
    c.observeField("fire", "onClock")
    c.control = "start"
    m.top.observeField("server", "onServer")
    m.top.observeField("params", "onParams")
    onServer()
    onParams()
    onClock()
end sub

sub onServer()
    if m.top.server = "" or m.started then return
    m.started = true
    startRequest(serverUrl(m.top.server) + "/api/screensaver", "GET", "", "onPhotos")
end sub

sub onParams()
    p = m.top.params
    if p = invalid then return
    w = p.weather
    if w <> invalid and w.icon <> invalid
        m.top.findNode("wIcon").uri = "pkg:/images/w_" + w.icon + ".png"
        m.top.findNode("weather").text = w.temp.toStr() + Chr(176) + "  " + w.text + "  in " + w.place
    end if
end sub

sub onClock()
    dt = CreateObject("roDateTime")
    dt.toLocalTime()
    clock24 = false
    if m.top.params <> invalid and m.top.params.clock24 = true then clock24 = true
    m.top.findNode("clock").text = hhmm(dt, clock24)
    months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
    m.top.findNode("date").text = dt.getWeekday() + ", " + months[dt.getMonth() - 1] + " " + dt.getDayOfMonth().toStr()
end sub

sub onPhotos(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    if data = invalid or data.photos = invalid or data.photos.count() = 0
        m.photos = ["pkg:/images/bg.jpg"]
    else
        m.photos = data.photos
    end if
    nextPhoto()
    m.top.findNode("nextTimer").control = "start"
end sub

sub nextPhoto()
    if m.photos.count() = 0 then return
    url = m.photos[m.pos]
    if Left(url, 4) <> "pkg:" then url = absUrl(m.top.server, url)
    m.pos = (m.pos + 1) mod m.photos.count()
    m.back.uri = url
end sub

sub onLoad(msg as Object)
    p = msg.getRoSGNode()
    if not p.isSameNode(m.back) or msg.getData() <> "ready" then return
    ' alternate the drift direction each photo
    m.flip = 1 - m.flip
    endPos = [-30, -16]
    if m.flip = 1 then endPos = [30, -10]
    kb = m.top.findNode("kenburns")
    kb.control = "stop"
    m.top.findNode("kbScale").fieldToInterp = m.back.id + ".scale"
    m.top.findNode("kbMove").fieldToInterp = m.back.id + ".translation"
    m.top.findNode("kbMove").keyValue = [[0, 0], endPos]
    kb.control = "start"
    x = m.top.findNode("xfade")
    x.control = "stop"
    m.top.findNode("xIn").fieldToInterp = m.back.id + ".opacity"
    m.top.findNode("xOut").fieldToInterp = m.front.id + ".opacity"
    x.control = "start"
    tmp = m.front
    m.front = m.back
    m.back = tmp
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if press then m.top.done = true
    return true
end function
