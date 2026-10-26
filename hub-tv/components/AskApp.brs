sub init()
    m.top.findNode("question").font = fontFile("regular", 22)
    m.top.findNode("answer").font = fontFile("medium", 32)
    m.top.findNode("hint").font = fontFile("regular", 17)
    m.pick = invalid
    m.fromPhone = false
    m.dialog = invalid
    m.thinking = false
    m.top.findNode("spinAnim").control = "start"
    m.top.findNode("breathAnim").control = "start"
    m.top.observeField("params", "onParams")
    m.top.observeField("remoteAsk", "onRemoteAsk")
    t = m.top.findNode("startTimer")
    t.observeField("fire", "onStart")
    t.control = "start"
    m.idle = m.top.findNode("idleTimer")
    m.idle.observeField("fire", "onIdle")
end sub

sub onParams()
    p = m.top.params
    if p = invalid then return
    if p.accent <> invalid then m.top.findNode("aura").blendColor = rokuColor(p.accent, "FF")
    if p.q <> invalid
        m.fromPhone = true
        showAnswer(p.q, p.answer, p.pick)
    end if
end sub

sub onStart()
    if not m.fromPhone
        setHint("")
        m.top.findNode("answer").text = "Ask me anything"
        m.top.findNode("question").text = ""
        askDialog()
    end if
end sub

sub onRemoteAsk()
    ev = m.top.remoteAsk
    if ev = invalid then return
    closeDialog()
    m.fromPhone = true
    showAnswer(ev.q, ev.answer, ev.pick)
end sub

sub setHint(text as String)
    m.top.findNode("hint").text = text
end sub

sub askDialog()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Ask Hub"
    dlg.message = ["Try: what should we watch?  Will it rain tomorrow?  Turn off in 30 minutes.  Open Netflix."]
    dlg.buttons = ["Ask", "Cancel"]
    dlg.observeField("buttonSelected", "onDialogButton")
    dlg.observeField("wasClosed", "onDialogClosed")
    m.dialog = dlg
    m.top.getScene().dialog = dlg
end sub

sub closeDialog()
    if m.dialog <> invalid
        m.dialog.unobserveField("buttonSelected")
        m.dialog.unobserveField("wasClosed")
        m.dialog.close = true
    end if
    m.dialog = invalid
    m.top.setFocus(true)
end sub

sub onDialogButton()
    idx = m.dialog.buttonSelected
    q = m.dialog.text.trim()
    closeDialog()
    if idx = 0 and q <> ""
        ask(q)
    else if m.top.findNode("question").text = ""
        m.top.done = true
    else
        setHint("OK to ask another     Back to Hub")
    end if
end sub

sub onDialogClosed()
    closeDialog()
    if m.top.findNode("question").text = "" and not m.thinking then m.top.done = true
end sub

sub ask(q as String)
    m.thinking = true
    m.pick = invalid
    m.top.findNode("question").text = q
    m.top.findNode("answer").text = "Checking..."
    setHint("")
    m.top.findNode("breathAnim").control = "stop"
    m.top.findNode("thinkAnim").control = "start"
    startRequest(serverUrl(m.top.server) + "/api/ask", "POST", FormatJson({ "q": q, "source": "tv" }), "onAnswer")
end sub

sub onAnswer(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    q = m.top.findNode("question").text
    if data = invalid
        showAnswer(q, "I can't reach the Hub server right now.", invalid)
    else
        showAnswer(q, data.answer, data.pick)
    end if
end sub

sub showAnswer(q as Dynamic, a as Dynamic, pick as Dynamic)
    m.thinking = false
    m.top.findNode("thinkAnim").control = "stop"
    m.top.findNode("aura").opacity = 0.32
    m.top.findNode("breathAnim").control = "start"
    if q = invalid then q = ""
    if a = invalid then a = ""
    m.top.findNode("question").text = q
    m.top.findNode("answer").text = a
    m.top.findNode("answerAnim").control = "start"
    m.pick = invalid
    if pick <> invalid and pick <> "" then m.pick = pick
    if m.pick <> invalid
        setHint("OK to watch it     Play to ask another     Back to Hub")
    else
        setHint("OK to ask another     Back to Hub")
    end if
    m.idle.control = "stop"
    if m.fromPhone then m.idle.control = "start"
end sub

sub onIdle()
    if m.fromPhone and m.dialog = invalid and not m.thinking then m.top.done = true
end sub

sub onPlayed(msg as Object)
    releaseTask(msg.getRoSGNode())
    if msg.getData().ok then m.top.done = true
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    m.idle.control = "stop"
    if m.thinking then return key <> "back"
    if key = "OK"
        if m.pick <> invalid
            m.top.findNode("answer").text = "Opening..."
            startRequest(serverUrl(m.top.server) + "/api/shows/play", "POST", FormatJson({ "id": m.pick }), "onPlayed")
        else
            m.fromPhone = false
            askDialog()
        end if
        return true
    else if key = "play"
        m.fromPhone = false
        askDialog()
        return true
    end if
    return false
end function
