sub init()
    m.list = m.top.findNode("list")
    m.list.observeField("itemFocused", "onFocused")
    m.list.observeField("itemSelected", "onSelected")
    m.top.findNode("title").font = fontFile("title", 46)
    m.top.findNode("crumb").font = makeFont(false, 20)
    m.top.findNode("previewName").font = makeFont(true, 24)
    m.top.findNode("previewInfo").font = makeFont(false, 19)
    m.top.findNode("empty").font = makeFont(false, 22)
    m.top.findNode("hint").font = makeFont(false, 17)
    m.path = ""
    m.entries = []
    m.player = invalid
    m.started = false
    m.top.observeField("server", "onServer")
    onServer()
end sub

sub onServer()
    if m.started or m.top.server = "" then return
    m.started = true
    load("")
end sub

sub load(path as String)
    m.path = path
    crumb = "Your server"
    if path <> "" then crumb = crumb + " / " + path.replace("/", " / ")
    m.top.findNode("crumb").text = crumb
    startRequest(serverUrl(m.top.server) + "/api/library?path=" + urlEncode(path, false), "GET", "", "onListing")
end sub

sub onListing(msg as Object)
    releaseTask(msg.getRoSGNode())
    r = msg.getData()
    data = invalid
    if r.ok then data = ParseJson(r.body)
    empty = m.top.findNode("empty")
    if data = invalid
        empty.text = "Can't reach the Hub server. Check that the Hub Server app is running in CasaOS."
        empty.visible = true
        m.list.visible = false
        return
    end if
    m.entries = []
    content = CreateObject("roSGNode", "ContentNode")
    for each d in data.dirs
        m.entries.push({ kind: "folder", name: d.name, path: d.path })
        item = content.createChild("ContentNode")
        item.title = d.name + "   >"
    end for
    for each f in data.files
        m.entries.push({ kind: f.kind, name: f.name, path: f.path, size: f.size })
        item = content.createChild("ContentNode")
        item.title = f.name
    end for
    if m.entries.count() = 0
        empty.text = "Nothing playable here. Videos, music and photos show up in this list."
        if m.path = "" then empty.text = "No media folders found. In CasaOS, add a volume that maps /DATA to /media in the Hub Server app."
        empty.visible = true
        m.list.visible = false
        m.top.setFocus(true)
    else
        empty.visible = false
        m.list.visible = true
        m.list.content = content
        m.list.jumpToItem = 0
        m.list.setFocus(true)
        onFocused()
    end if
end sub

sub onFocused()
    i = m.list.itemFocused
    if i < 0 or i >= m.entries.count() then return
    e = m.entries[i]
    img = m.top.findNode("previewImg")
    icon = m.top.findNode("previewIcon")
    m.top.findNode("previewName").text = e.name
    info = ""
    img.uri = ""
    icon.uri = ""
    if e.kind = "folder"
        icon.uri = "pkg:/images/library.png"
        info = "Folder"
    else if e.kind = "photo"
        img.uri = serverUrl(m.top.server) + "/api/img?w=440&h=248&fit=contain&src=" + urlEncode(e.path, false)
        info = "Photo"
    else
        icon.uri = "pkg:/images/library.png"
        info = "Video"
        if e.kind = "audio" then info = "Music"
    end if
    if e.size <> invalid then info = info + ", " + Str(Int(e.size / 104857.6) / 10).trim() + " MB"
    m.top.findNode("previewInfo").text = info
end sub

sub onSelected()
    i = m.list.itemSelected
    if i < 0 or i >= m.entries.count() then return
    e = m.entries[i]
    if e.kind = "folder"
        load(e.path)
        return
    end if
    items = []
    index = 0
    if e.kind = "photo"
        for each x in m.entries
            if x.kind = "photo"
                if x.path = e.path then index = items.count()
                items.push({ kind: "photo", src: x.path, title: x.name })
            end if
        end for
    end if
    m.player = CreateObject("roSGNode", "PlayerApp")
    m.player.server = m.top.server
    m.player.items = items
    m.player.index = index
    m.player.observeField("done", "onPlayerDone")
    m.top.appendChild(m.player)
    m.player.params = { kind: e.kind, src: e.path, title: e.name }
    m.player.setFocus(true)
end sub

sub onPlayerDone()
    if m.player = invalid then return
    m.player.unobserveField("done")
    m.top.removeChild(m.player)
    m.player = invalid
    m.list.setFocus(true)
end sub

function onKeyEvent(key as String, press as Boolean) as Boolean
    if not press then return false
    if key = "back"
        if m.player <> invalid
            onPlayerDone()
            return true
        end if
        if m.path <> ""
            idx = 0
            last = 0
            while true
                idx = Instr(idx + 1, m.path, "/")
                if idx = 0 then exit while
                last = idx
            end while
            parent = ""
            if last > 0 then parent = Left(m.path, last - 1)
            load(parent)
            return true
        end if
    end if
    return false
end function
