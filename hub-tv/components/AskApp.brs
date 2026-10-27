sub init()
    m.top.findNode("question").font = fontFile("medium", 22)
    m.top.findNode("answer").font = fontFile("title", 36)
    m.top.findNode("hint").font = fontFile("regular", 17)
    m.rec = invalid
    m.fromPhone = false
    m.dialog = invalid
    m.thinking = false
    a = m.top.findNode("answerAnim")
    springFade(a, m.top.findNode("answerFade"), 0.0, 1.0, "gentle")
    springTo(invalid, m.top.findNode("answerMove"), [140, 432], [140, 414], "bouncy")
    setVoice(false)
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
    if p.accent <> invalid then m.top.findNode("bar2").blendColor = rokuColor(p.accent, "FF")
    if p.q <> invalid
        m.fromPhone = true
        showAnswer(p.q, p.answer, p.rec)
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
    showAnswer(ev.q, ev.answer, ev.rec)
end sub

' Idle: slow, small moves. Thinking: quick, tall moves. Each bar has its own speed so
' they never line up.
sub setVoice(busy as Boolean)
    lows = [0.55, 0.4, 0.3, 0.45, 0.6]
    for i = 0 to 4
        a = m.top.findNode("barAnim" + i.toStr())
        a.control = "stop"
        secs = 2.6 + i * 0.37
        lo = 0.8
        if busy
            secs = 0.62 + i * 0.09
            lo = lows[i]
        end if
        driftLoop(a, m.top.findNode("barInterp" + i.toStr()), [1.0, 1.0], [1.0, lo], secs)
        a.control = "start"
    end for
end sub

sub setHint(text as String)
    m.top.findNode("hint").text = text
end sub

sub askDialog()
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Ask Hub"
    dlg.message = ["Try: recommend a funny movie.  Will it rain tomorrow?  Turn off in 30 minutes.  Open Netflix."]
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
    m.rec = invalid
    m.top.findNode("question").text = q
    m.top.findNode("answer").text = "Checking..."
    setHint("")
    setVoice(true)
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
        showAnswer(q, data.answer, data.rec)
    end if
end sub

sub showAnswer(q as Dynamic, a as Dynamic, rec as Dynamic)
    m.thinking = false
    setVoice(false)
    if q = invalid then q = ""
    if a = invalid then a = ""
    m.top.findNode("question").text = q
    m.top.findNode("answer").text = a
    m.top.findNode("answerAnim").control = "start"
    m.rec = invalid
    if rec <> invalid and type(rec) = "roAssociativeArray" and rec.title <> invalid then m.rec = rec
    if m.rec <> invalid
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
        if m.rec <> invalid
            m.top.findNode("answer").text = "Opening " + m.rec.title + "..."
            body = { "title": m.rec.title, "kind": m.rec.kind, "app": m.rec.app, "appName": m.rec.appName }
            startRequest(serverUrl(m.top.server) + "/api/recommend/watch", "POST", FormatJson(body), "onPlayed")
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
