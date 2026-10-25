sub init()
    m.front = m.top.findNode("photoA")
    m.back = m.top.findNode("photoB")
    m.front.observeField("loadStatus", "onPhotoLoad")
    m.back.observeField("loadStatus", "onPhotoLoad")
    m.video = m.top.findNode("video")
    m.video.observeField("state", "onVideoState")
    m.audio = m.top.findNode("audio")
    m.audio.observeField("state", "onAudioState")
    m.audio.observeField("position", "onAudioPosition")
    m.top.findNode("captionText").font = makeFont(false, 20)
    m.top.findNode("audioTitle").font = makeFont(true, 38)
    m.top.findNode("audioTime").font = makeFont(false, 24)
    m.top.findNode("errorText").font = makeFont(false, 24)
    m.slide = m.top.findNode("slideTimer")
    m.slide.observeField("fire", "onSlide")
    m.kind = ""
    m.photos = []
    m.pos = 0
    m.first = true
    m.sliding = false
    m.top.observeField("params", "onParams")
    m.top.observeField("focusedChild", "onFocusChange")
end sub

' Hub focuses this component after adding it; hand focus to the video so its
' built-in controls (play/pause, seek) work.
sub onFocusChange()
    if m.top.hasFocus() and m.kind = "video" and m.video.visible then m.video.setFocus(true)
end sub

sub onParams()
    p = m.top.params
    if p = invalid or p.src = invalid then return
    m.kind = p.kind
    if m.kind = invalid then m.kind = "photo"
    title = p.title
    if title = invalid then title = ""
    if m.kind = "photo"
        m.photos = m.top.items
        m.pos = m.top.index
        if m.photos = invalid or m.photos.count() = 0
            m.photos = [{ kind: "photo", src: p.src, title: title }]
            m.pos = 0
        end if
        showPhoto()
        m.top.setFocus(true)
    else if m.kind = "audio"
        m.top.findNode("audioView").visible = true
        m.top.findNode("audioTitle").text = title
        c = CreateObject("roSGNode", "ContentNode")
        c.url = mediaUrl(m.top.server, p.src)
        c.title = title
        c.streamFormat = formatFor(p.src)
        m.audio.content = c
        m.audio.control = "play"
        m.top.setFocus(true)
    else
        c = CreateObject("roSGNode", "ContentNode")
        c.url = mediaUrl(m.top.server, p.src)
        c.title = title
        c.streamFormat = formatFor(p.src)
        m.video.content = c
        m.video.visible = true
        m.video.control = "play"
        m.video.setFocus(true)
    end if
end sub

function formatFor(src as String) as String
    s = LCase(src)
    if Right(s, 4) = ".mkv" then return "mkv"
    if Right(s, 5) = ".m3u8" then return "hls"
    if Right(s, 4) = ".mp3" then return "mp3"
    if Right(s, 5) = ".flac" then return "flac"
    if Right(s, 4) = ".wav" then return "wav"
    if Right(s, 4) = ".m4a" or Right(s, 4) = ".aac" then return "mp4"
    if Right(s, 3) = ".ts" then return "ts"
    return "mp4"
end function

' ---------- photos ----------

function photoUrl(src as String) as String
    if Left(src, 4) = "http" then return src
    return serverUrl(m.top.server) + "/api/img?w=1280&h=720&fit=contain&src=" + urlEncode(src, false)
end function

sub showPhoto()
    item = m.photos[m.pos]
    m.back.opacity = 0
    m.back.uri = photoUrl(item.src)
    label = item.title
    if m.photos.count() > 1 then label = label + "      " + (m.pos + 1).toStr() + " of " + m.photos.count().toStr() + "      Left/Right to browse, Play for slideshow"
    m.top.findNode("captionText").text = label
end sub

sub onPhotoLoad(msg as Object)
    p = msg.getRoSGNode()
    if not p.isSameNode(m.back) then return
    if msg.getData() = "ready"
        fade = m.top.findNode("fadeAnim")
        fade.control = "stop"
        m.top.findNode("fadeIn").fieldToInterp = m.back.id + ".opacity"
        m.top.findNode("fadeOut").fieldToInterp = m.front.id + ".opacity"
        fade.control = "start"
        tmp = m.front
        m.front = m.back
        m.back = tmp
        if m.first
            m.first = false
            m.top.findNode("captionAnim").control = "start"
        end if
    else if msg.getData() = "failed"
        showError("Couldn't load this photo.")
    end if
end sub

sub browse(delta as Integer)
    if m.photos.count() < 2 then return
    m.pos = (m.pos + delta + m.photos.count()) mod m.photos.count()
    showPhoto()
    a = m.top.findNode("captionAnim")
    a.control = "stop"
    a.control = "start"
end sub

sub onSlide()
    browse(1)
end sub

' ---------- video / audio ----------

sub onVideoState()
    st = m.video.state
    if st = "finished"
        m.top.done = true
    else if st = "error"
        showError("This video can't play on the TV (" + m.video.errorMsg + "). MP4 and MKV with H.264/H.265 work best.")
        m.video.visible = false
        m.top.setFocus(true)
    end if
end sub

sub onAudioState()
    if m.audio.state = "finished" then m.top.done = true
    if m.audio.state = "error" then showError("This file can't play on the TV.")
end sub

sub onAudioPosition()
    secs = Int(m.audio.position)
    s = (secs mod 60).toStr()
    if secs mod 60 < 10 then s = "0" + s
    label = Int(secs / 60).toStr() + ":" + s
    if m.audio.state = "paused" then label = label + "   paused"
    m.top.findNode("audioTime").text = label
end sub

sub showError(text as String)
    e = m.top.findNode("errorText")
    e.text = text
    e.visible = true
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "back"
        m.video.control = "stop"
        m.audio.control = "stop"
        m.slide.control = "stop"
        m.top.done = true
        return true
    end if
    if m.kind = "photo"
        if key = "right"
            browse(1)
            return true
        else if key = "left"
            browse(-1)
            return true
        else if key = "play" or key = "OK"
            m.sliding = not m.sliding
            if m.sliding
                m.slide.control = "start"
                browse(1)
            else
                m.slide.control = "stop"
            end if
            return true
        end if
    else if m.kind = "audio"
        if key = "play" or key = "OK"
            if m.audio.state = "paused"
                m.audio.control = "resume"
            else
                m.audio.control = "pause"
            end if
            return true
        end if
    end if
    return false
end function
