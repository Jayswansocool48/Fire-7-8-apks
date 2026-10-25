' Remote browser client.
'   Arrows   move the pointer (hold to speed up; push past the top/bottom edge to scroll)
'   OK       click            Rew / FF   scroll a page up / down
'   Play     type text        *          menu (address, enter, reload, exit...)
'   Back     previous page
' The phone's Pointer pad moves the same pointer (events arrive in the `remote` field).
sub init()
    m.pageA = m.top.findNode("pageA")
    m.pageB = m.top.findNode("pageB")
    m.pageA.observeField("loadStatus", "onPosterLoad")
    m.pageB.observeField("loadStatus", "onPosterLoad")
    m.front = m.pageA
    m.backP = m.pageB
    m.cursor = m.top.findNode("cursor")
    m.statusBar = m.top.findNode("statusBar")
    m.status = m.top.findNode("status")
    m.status.font = makeFont(false, 19)
    m.cx = 640
    m.cy = 360
    m.heldKey = ""
    m.heldTicks = 0
    m.busy = false
    m.frameNum = 0
    m.connected = false
    m.streaming = false
    m.dialog = invalid
    m.dialogKind = ""
    m.title = ""
    m.url = ""
    m.client = "tv" + CreateObject("roDeviceInfo").getRandomUUID().left(6)

    m.repeatTimer = m.top.findNode("repeatTimer")
    m.repeatTimer.observeField("fire", "onRepeat")
    m.statusTimer = m.top.findNode("statusTimer")
    m.statusTimer.observeField("fire", "onStatusTimeout")
    m.retryTimer = m.top.findNode("retryTimer")
    m.retryTimer.observeField("fire", "nextFrame")
    m.top.observeField("refreshNow", "onRefreshNow")
    m.top.observeField("remote", "onRemote")
    placeCursor()

    ' Dialogs need the scene, which exists once we're attached - so start a tick later.
    t = m.top.findNode("startTimer")
    t.observeField("fire", "onStart")
    t.control = "start"
end sub

sub onStart()
    if m.top.server = ""
        askServer()
    else
        connect()
    end if
end sub

' ---------- server ----------

function baseUrl() as String
    return serverUrl(m.top.server)
end function

sub connect()
    showStatus("Connecting...", false)
    sendAction({ "type": "status" })
end sub

sub sendAction(a as Object)
    m.busy = true
    startRequest(baseUrl() + "/action", "POST", FormatJson(a), "onActionResponse")
end sub

sub onActionResponse(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    m.busy = false
    if not r.ok
        m.connected = false
        showError(r.body)
        return
    end if
    info = ParseJson(r.body)
    if info <> invalid
        if info.title <> invalid then m.title = info.title
        if info.url <> invalid then m.url = info.url
    end if
    if not m.connected
        m.connected = true
        showStatus("Arrows move   OK click   Rew/FF scroll   Play type   * menu   Back goes back", true)
        startStream()
    else if m.title <> ""
        showStatus(m.title, true)
    end if
end sub

sub onRefreshNow()
    if m.connected
        sendAction({ "type": "status" })
    else if m.top.server <> ""
        connect()
    end if
end sub

' ---------- frames: ask for the next one as soon as the last arrives ----------

sub startStream()
    if m.streaming then return
    m.streaming = true
    nextFrame()
end sub

sub nextFrame()
    if not m.connected then
        m.streaming = false
        return
    end if
    m.frameNum += 1
    m.backP.uri = baseUrl() + "/frame.jpg?wait=1&c=" + m.client + "&n=" + m.frameNum.toStr()
end sub

sub onPosterLoad(msg as Object)
    p = msg.getRoSGNode()
    if not p.isSameNode(m.backP) then return
    st = msg.getData()
    if st = "ready"
        m.backP.visible = true
        m.front.visible = false
        tmp = m.front
        m.front = m.backP
        m.backP = tmp
        nextFrame()
    else if st = "failed"
        m.retryTimer.control = "start"
    end if
end sub

' ---------- pointer ----------

sub placeCursor()
    m.cursor.translation = [m.cx - 2, m.cy - 2]
end sub

sub moveBy(dx as Integer, dy as Integer)
    m.cx += dx
    m.cy += dy
    if m.cx < 0 then m.cx = 0
    if m.cx > 1279 then m.cx = 1279
    if m.cy < 0
        m.cy = 0
        if m.connected and not m.busy then sendAction({ "type": "scroll", x: m.cx, y: m.cy, dy: -220 })
    else if m.cy > 719
        m.cy = 719
        if m.connected and not m.busy then sendAction({ "type": "scroll", x: m.cx, y: m.cy, dy: 220 })
    end if
    placeCursor()
end sub

sub moveKey(key as String, stepPx as Integer)
    if key = "left" then moveBy(-stepPx, 0)
    if key = "right" then moveBy(stepPx, 0)
    if key = "up" then moveBy(0, -stepPx)
    if key = "down" then moveBy(0, stepPx)
end sub

sub onRepeat()
    if m.heldKey = "" then return
    m.heldTicks += 1
    if m.heldTicks < 5 then return
    speed = 6 + m.heldTicks
    if speed > 38 then speed = 38
    moveKey(m.heldKey, speed)
end sub

sub onRemote()
    ev = m.top.remote
    if ev = invalid or not m.connected then return
    if ev.type = "pointer"
        dx = 0
        dy = 0
        if ev.dx <> invalid then dx = ev.dx
        if ev.dy <> invalid then dy = ev.dy
        moveBy(dx, dy)
    else if ev.type = "click"
        sendAction({ "type": "click", x: m.cx, y: m.cy })
    else if ev.type = "scroll"
        dy = 300
        if ev.dy <> invalid then dy = ev.dy
        sendAction({ "type": "scroll", x: m.cx, y: m.cy, dy: dy })
    end if
end sub

' ---------- status bar ----------

sub showStatus(text as String, autoHide as Boolean)
    m.status.text = text
    m.top.findNode("statusAnim").control = "stop"
    m.statusBar.opacity = 1
    m.statusTimer.control = "stop"
    if autoHide then m.statusTimer.control = "start"
end sub

sub onStatusTimeout()
    m.top.findNode("statusAnim").control = "start"
end sub

' ---------- dialogs ----------

sub openDialog(dlg as Object, kind as String)
    m.dialog = dlg
    m.dialogKind = kind
    dlg.observeField("buttonSelected", "onDialogButton")
    dlg.observeField("wasClosed", "onDialogClosed")
    m.top.getScene().dialog = dlg
end sub

sub closeDialog()
    if m.dialog <> invalid
        m.dialog.unobserveField("buttonSelected")
        m.dialog.unobserveField("wasClosed")
        m.dialog.close = true
    end if
    m.dialog = invalid
    m.dialogKind = ""
    m.top.setFocus(true)
end sub

sub onDialogClosed()
    kind = m.dialogKind
    closeDialog()
    if kind = "server" and m.top.server = "" then m.top.done = true
end sub

function keyboard(title as String, message as Object, text as String, buttons as Object) as Object
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = title
    dlg.message = message
    dlg.text = text
    dlg.buttons = buttons
    return dlg
end function

function menuDialog(title as String, message as Object, buttons as Object) as Object
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = title
    dlg.message = message
    dlg.buttons = buttons
    return dlg
end function

sub askServer()
    openDialog(keyboard("Hub server", ["Your CasaOS server's IP address, e.g. 10.0.0.134"], m.top.server, ["Connect", "Cancel"]), "server")
end sub

sub askUrl()
    openDialog(keyboard("Go to", ["A web address, or words to search for."], "", ["Go", "Cancel"]), "url")
end sub

sub askType()
    openDialog(keyboard("Type text", ["Click a text box on the page first, then type here."], "", ["Type", "Type + Enter", "Cancel"]), "type")
end sub

sub showMenu()
    m.menuActions = ["url", "enter", "backspace", "forward", "reload", "home", "exit"]
    openDialog(menuDialog("Browser", [m.title], ["Go to address or search", "Press Enter", "Backspace", "Forward", "Reload", "Home page", "Exit to Hub"]), "menu")
end sub

sub showError(detail as String)
    openDialog(menuDialog("Can't reach the Hub server", [baseUrl(), "Make sure the Hub Server app is running in CasaOS and the TV and server are on the same network.", "(" + detail + ")"], ["Retry", "Change server", "Exit to Hub"]), "error")
end sub

sub onDialogButton()
    kind = m.dialogKind
    idx = m.dialog.buttonSelected
    text = ""
    if kind = "server" or kind = "url" or kind = "type" then text = m.dialog.text.trim()
    closeDialog()
    if kind = "server"
        if idx = 0 and text <> ""
            m.top.server = text
            regWrite("browserServer", text)
            connect()
        else if m.top.server = ""
            m.top.done = true
        end if
    else if kind = "url"
        if idx = 0 and text <> "" then sendAction({ "type": "goto", url: text })
    else if kind = "type"
        if idx < 2 and text <> "" then sendAction({ "type": "type", text: text, enter: (idx = 1) })
    else if kind = "error"
        if idx = 0
            connect()
        else if idx = 1
            askServer()
        else
            m.top.done = true
        end if
    else if kind = "menu"
        action = m.menuActions[idx]
        if action = "url"
            askUrl()
        else if action = "enter"
            sendAction({ "type": "key", key: "Enter" })
        else if action = "backspace"
            sendAction({ "type": "key", key: "Backspace" })
        else if action = "forward" or action = "reload" or action = "home"
            sendAction({ "type": action })
        else if action = "exit"
            m.top.done = true
        end if
    end if
end sub

' ---------- remote ----------

function onKeyEvent(key as String, press as Boolean) as Boolean
    if key = "up" or key = "down" or key = "left" or key = "right"
        if press
            m.heldKey = key
            m.heldTicks = 0
            moveKey(key, 20)
            m.repeatTimer.control = "start"
        else if m.heldKey = key
            m.heldKey = ""
            m.repeatTimer.control = "stop"
        end if
        return true
    end if
    if not press then return false
    if not m.connected
        if key = "back" then return false
        if key = "options" then askServer()
        return true
    end if
    if key = "OK"
        sendAction({ "type": "click", x: m.cx, y: m.cy })
    else if key = "fastforward"
        sendAction({ "type": "scroll", x: m.cx, y: m.cy, dy: 600 })
    else if key = "rewind"
        sendAction({ "type": "scroll", x: m.cx, y: m.cy, dy: -600 })
    else if key = "play"
        askType()
    else if key = "options"
        showMenu()
    else if key = "back"
        sendAction({ "type": "back" })
    else
        return false
    end if
    return true
end function
