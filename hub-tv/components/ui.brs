' Shared building blocks for Hub's screens: tiles, labels, buttons, chips and spring moves.
' Every screen that includes this needs common.brs too.

' ---------------------------------------------------------------- spring moves on any node

' Springs node.field (translation, scale, opacity or any Float field) from its current value
' to `target`. A new call on the same node and field picks up from wherever the last one is.
sub animTo(node as Object, field as String, target as Dynamic, preset as String)
    if node = invalid then return
    ' Animations live directly under the component: Roku looks up fieldToInterp ids from the
    ' animation's parent down, so a parent that holds the whole component reaches every node.
    if m.anims = invalid
        m.anims = {}
        m.animSeq = 0
    end if
    if node.id = ""
        m.animSeq = m.animSeq + 1
        node.id = "an" + m.animSeq.toStr()
    end if
    k = node.id + "." + field
    a = m.anims[k]
    if a = invalid
        a = m.top.createChild("Animation")
        if type(target) = "roArray"
            interp = a.createChild("Vector2DFieldInterpolator")
        else
            interp = a.createChild("FloatFieldInterpolator")
        end if
        interp.fieldToInterp = k
        m.anims[k] = a
    else
        a.control = "stop"
        interp = a.getChild(0)
    end if
    cur = node.getField(field)
    if field = "opacity"
        springFade(a, interp, cur, target, preset)
    else
        springTo(a, interp, cur, target, preset)
    end if
    a.control = "start"
end sub

' ---------------------------------------------------------------- building nodes

function uiLabel(parent as Object, text as String, x as Integer, y as Integer, w as Integer, font as Object, color as String) as Object
    lbl = parent.createChild("Label")
    lbl.translation = [x, y]
    if w > 0 then lbl.width = w
    lbl.font = font
    lbl.color = color
    lbl.text = text
    return lbl
end function

function uiImage(parent as Object, uri as String, x as Integer, y as Integer, w as Integer, h as Integer) as Object
    p = parent.createChild("Poster")
    p.uri = uri
    p.translation = [x, y]
    p.width = w
    p.height = h
    return p
end function

function uiPatch(parent as Object, uri as String, x as Integer, y as Integer, w as Integer, h as Integer, color as String) as Object
    p = uiImage(parent, uri, x, y, w, h)
    p.blendColor = color
    return p
end function

' A focusable tile: child 0 is the focus ring, child 1 the face. Content goes on top.
function uiTile(parent as Object, id as String, w as Integer, h as Integer, face as String) as Object
    g = parent.createChild("Group")
    g.id = id
    g.scaleRotateCenter = [w / 2, h / 2]
    ring = g.createChild("Poster")
    ring.id = id + "_ring"
    ring.uri = "pkg:/images/ring.9.png"
    ring.width = w + 14
    ring.height = h + 14
    ring.translation = [-7, -7]
    ring.blendColor = pal("cream")
    ring.opacity = 0
    f = g.createChild("Poster")
    f.uri = "pkg:/images/card.9.png"
    f.width = w
    f.height = h
    f.blendColor = face
    return g
end function

' Artwork clipped to a mask, with an optional dark fade at the bottom for text.
function uiArt(tile as Object, uri as String, maskUri as String, w as Integer, h as Integer, shade as Boolean) as Object
    mask = tile.createChild("MaskGroup")
    mask.maskUri = maskUri
    mask.maskSize = [w, h]
    art = mask.createChild("Poster")
    art.uri = uri
    art.width = w
    art.height = h
    art.loadWidth = w
    art.loadHeight = h
    art.loadDisplayMode = "scaleToFill"
    if shade
        sh = mask.createChild("Poster")
        sh.uri = "pkg:/images/shade.png"
        sh.width = w
        sh.height = Int(h * 0.66)
        sh.translation = [0, h - Int(h * 0.66)]
    end if
    return mask
end function

sub uiFocus(tile as Object, focused as Boolean, scale as Float)
    if tile = invalid then return
    ring = tile.getChild(0)
    if focused
        animTo(tile, "scale", [scale, scale], "snappy")
        animTo(ring, "opacity", 1.0, "snappy")
    else
        animTo(tile, "scale", [1.0, 1.0], "snappy")
        animTo(ring, "opacity", 0.0, "snappy")
    end if
end sub

' A pill button. Returns {node, w}. Child 1 is the face, child 2 the label.
function uiButton(parent as Object, id as String, text as String, icon as String, font as Object) as Object
    g = parent.createChild("Group")
    g.id = id
    ring = g.createChild("Poster")
    ring.uri = "pkg:/images/pill.9.png"
    ring.blendColor = pal("cream")
    ring.opacity = 0
    bg = g.createChild("Poster")
    bg.uri = "pkg:/images/pill.9.png"
    bg.blendColor = "0xF2EADC26"
    lbl = g.createChild("Label")
    lbl.font = font
    lbl.text = text
    lbl.height = 52
    lbl.vertAlign = "center"
    lbl.color = pal("cream")
    tw = lbl.boundingRect().width
    padL = 26
    if icon <> ""
        ic = g.createChild("Poster")
        ic.uri = icon
        ic.width = 22
        ic.height = 22
        ic.translation = [22, 15]
        ic.blendColor = pal("cream")
        padL = 54
    end if
    w = tw + padL + 26
    lbl.translation = [padL, 0]
    bg.width = w
    bg.height = 52
    ring.width = w
    ring.height = 52
    g.scaleRotateCenter = [w / 2, 26]
    return { node: g, w: w }
end function

' Focused buttons turn cream with dark text, like a lit key.
sub uiButtonFocus(b as Object, focused as Boolean)
    if b = invalid then return
    g = b.node
    bg = g.getChild(1)
    lbl = g.getChild(2)
    ic = invalid
    if g.getChildCount() > 3 then ic = g.getChild(3)
    if focused
        bg.blendColor = pal("cream")
        lbl.color = pal("ink")
        if ic <> invalid then ic.blendColor = pal("ink")
        animTo(g, "scale", [1.06, 1.06], "snappy")
    else
        bg.blendColor = "0xF2EADC26"
        lbl.color = pal("cream")
        if ic <> invalid then ic.blendColor = pal("cream")
        animTo(g, "scale", [1.0, 1.0], "snappy")
    end if
end sub

' A row of buttons laid out left to right. Returns the list of {node, w, id}.
function uiButtons(parent as Object, prefix as String, specs as Object, font as Object) as Object
    out = []
    x = 0
    for i = 0 to specs.count() - 1
        s = specs[i]
        icon = ""
        if s.icon <> invalid then icon = s.icon
        b = uiButton(parent, prefix + i.toStr(), s.text, icon, font)
        b.node.translation = [x, 0]
        b.id = s.id
        out.push(b)
        x = x + b.w + 14
    end for
    return out
end function

' A small rounded chip with text, for "On your TV" and genres. Returns its width.
function uiChip(parent as Object, text as String, x as Integer, y as Integer, font as Object, face as String, ink as String) as Integer
    lbl = CreateObject("roSGNode", "Label")
    lbl.font = font
    lbl.text = text
    lbl.height = 34
    lbl.vertAlign = "center"
    lbl.color = ink
    w = lbl.boundingRect().width + 28
    uiPatch(parent, "pkg:/images/pill.9.png", x, y, w, 34, face)
    lbl.translation = [x + 14, y]
    parent.appendChild(lbl)
    return w
end function

' A round avatar with the profile's first letter.
function uiAvatar(parent as Object, name as String, color as String, x as Integer, y as Integer, size as Integer, font as Object) as Object
    g = parent.createChild("Group")
    g.translation = [x, y]
    c = uiPatch(g, "pkg:/images/circle.png", 0, 0, size, size, rokuColor(color, "FF"))
    initial = "?"
    if name <> invalid and name <> "" then initial = UCase(Left(name, 1))
    lbl = uiLabel(g, initial, 0, 0, size, font, pal("ink"))
    lbl.height = size
    lbl.horizAlign = "center"
    lbl.vertAlign = "center"
    return g
end function

'' ---------------------------------------------------------------- text

' True only for a real true. Comparing a missing JSON field with "= true" stops the channel,
' so every yes/no from the server goes through this.
function isTrue(v as Dynamic) as Boolean
    t = type(v)
    if t = "Boolean" or t = "roBoolean" then return v
    return false
end function

' A string field, or "" when the server left it out.
function txt(v as Dynamic) as String
    t = type(v)
    if t = "String" or t = "roString" then return v
    if t = "Integer" or t = "roInteger" or t = "Float" or t = "roFloat" or t = "Double" or t = "roDouble" or t = "LongInteger" then return v.toStr()
    return ""
end function

function agoOf(v as Dynamic) as String
    if v = invalid then return ""
    return agoText(v)
end function

' "2016 · Show · Sci-Fi, Horror" from whatever the item has.
function metaLine(it as Object) as String
    parts = []
    if txt(it.year) <> "" then parts.push(txt(it.year))
    if it.kind = "movie"
        parts.push("Movie")
    else if isTrue(it.anime)
        parts.push("Anime")
    else
        parts.push("Show")
    end if
    if it.genres <> invalid and it.genres.count() > 0
        g = it.genres[0]
        if it.genres.count() > 1 then g = g + ", " + it.genres[1]
        parts.push(g)
    end if
    if it.rating <> invalid and it.rating <> 0 then parts.push(Chr(9733) + " " + it.rating.toStr())
    out = ""
    for i = 0 to parts.count() - 1
        if i > 0 then out = out + "  " + Chr(183) + "  "
        out = out + parts[i]
    end for
    return out
end function

function joinText(items as Object, sep as String) as String
    out = ""
    for i = 0 to items.count() - 1
        if i > 0 then out = out + sep
        out = out + items[i]
    end for
    return out
end function

' The query string Hub's server needs to look a title up.
function titleQuery(it as Object) as String
    q = "?title=" + urlEncode(it.title, false)
    if it.kind <> invalid then q = q + "&kind=" + it.kind
    if txt(it.year) <> "" then q = q + "&year=" + txt(it.year)
    if it.tvmaze <> invalid then q = q + "&tvmaze=" + it.tvmaze.toStr()
    if it.anilist <> invalid then q = q + "&anilist=" + it.anilist.toStr()
    if isTrue(it.anime) then q = q + "&anime=1"
    return q
end function
