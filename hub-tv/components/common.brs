' Shared helpers: background HTTP requests, saved settings, formatting.

' Starts an HttpTask. `callback` is the name of a sub(msg) in the calling component.
function startRequest(url as String, method as String, body as String, callback as String) as Object
    t = CreateObject("roSGNode", "HttpTask")
    t.url = url
    t.method = method
    t.body = body
    t.observeField("response", callback)
    if m.pendingTasks = invalid then m.pendingTasks = []
    m.pendingTasks.push(t)
    t.control = "RUN"
    return t
end function

' Call from the callback once you have the response, so the task can be freed.
sub releaseTask(t as Object)
    if m.pendingTasks = invalid or t = invalid then return
    for i = 0 to m.pendingTasks.count() - 1
        if m.pendingTasks[i].isSameNode(t)
            t.unobserveField("response")
            m.pendingTasks.delete(i)
            return
        end if
    end for
end sub

function regRead(key as String, fallback as String) as String
    sec = CreateObject("roRegistrySection", "hub")
    if sec.exists(key) then return sec.read(key)
    return fallback
end function

sub regWrite(key as String, value as String)
    sec = CreateObject("roRegistrySection", "hub")
    sec.write(key, value)
    sec.flush()
end sub

' "192.168.1.5" or "192.168.1.5:9000" -> "http://192.168.1.5:8765"
function serverUrl(server as String) as String
    s = server
    if Instr(1, s, ":") = 0 then s = s + ":8765"
    return "http://" + s
end function

' A server path ("/api/x") or a full URL -> a full URL.
function absUrl(server as String, path as String) as String
    if Left(path, 4) = "http" then return path
    return serverUrl(server) + path
end function

' Hub's type: Fraunces for the clock, titles and quotes, Manrope for everything else.
' Both ship in pkg:/fonts under the SIL Open Font License.
function makeFont(bold as Boolean, size as Integer) as Object
    if bold then return fontFile("semibold", size)
    return fontFile("regular", size)
end function

' name: "clock" (Fraunces light), "title" (Fraunces), "quote" (Fraunces italic),
'       "regular", "medium", "semibold" (Manrope)
function fontFile(name as String, size as Integer) as Object
    f = CreateObject("roSGNode", "Font")
    f.uri = "pkg:/fonts/" + name + ".ttf"
    f.size = size
    return f
end function

' "5 min ago", "2 h ago", "yesterday"
function agoText(epoch as Dynamic) as String
    now = CreateObject("roDateTime").asSeconds()
    s = now - Int(epoch)
    if s < 60 then return "just now"
    if s < 3600 then return Int(s / 60).toStr() + " min ago"
    if s < 86400 then return Int(s / 3600).toStr() + " h ago"
    if s < 172800 then return "yesterday"
    return Int(s / 86400).toStr() + " days ago"
end function

' A poster or thumbnail from the internet, fetched and cached by the Hub server.
function thumbUrl(server as String, url as Dynamic) as String
    if url = invalid or url = "" then return ""
    if Left(url, 4) <> "http" then return absUrl(server, url)
    return serverUrl(server) + "/api/thumb?u=" + urlEncode(url, false)
end function

function deviceIp() as String
    addrs = CreateObject("roDeviceInfo").getIPAddrs()
    for each k in addrs
        return addrs[k]
    end for
    return ""
end function

' 12345 -> "12,345"
function withCommas(n as Dynamic) as String
    if type(n) = "Invalid" or type(n) = "roInvalid" then n = 0
    s = Int(n).toStr()
    out = ""
    count = 0
    for i = s.len() to 1 step -1
        out = s.mid(i - 1, 1) + out
        count += 1
        if count mod 3 = 0 and i > 1 then out = "," + out
    end for
    return out
end function

' Percent-encodes text for a URL (UTF-8). Keeps "/" when keepSlash is true.
function urlEncode(text as String, keepSlash as Boolean) as String
    safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    hexd = "0123456789ABCDEF"
    out = ""
    for i = 0 to text.len() - 1
        ch = text.mid(i, 1)
        if Instr(1, safe, ch) > 0 or (keepSlash and ch = "/")
            out = out + ch
        else
            c = Asc(ch)
            bytes = []
            if c < &h80
                bytes = [c]
            else if c < &h800
                bytes = [&hC0 or (c >> 6), &h80 or (c and &h3F)]
            else
                bytes = [&hE0 or (c >> 12), &h80 or ((c >> 6) and &h3F), &h80 or (c and &h3F)]
            end if
            for each b in bytes
                out = out + "%" + hexd.mid(b >> 4, 1) + hexd.mid(b and 15, 1)
            end for
        end if
    end for
    return out
end function

function mediaUrl(server as String, vpath as String) as String
    if Left(vpath, 4) = "http" then return vpath
    return serverUrl(server) + "/media/" + urlEncode(vpath, true)
end function

' Epoch seconds -> "7:30 PM" (or "19:30").
function clockText(epoch as Dynamic, clock24 as Boolean) as String
    dt = CreateObject("roDateTime")
    dt.fromSeconds(Int(epoch))
    dt.toLocalTime()
    return hhmm(dt, clock24)
end function

function hhmm(dt as Object, clock24 as Boolean) as String
    h = dt.getHours()
    mins = dt.getMinutes()
    mm = mins.toStr()
    if mins < 10 then mm = "0" + mm
    if clock24
        hh = h.toStr()
        if h < 10 then hh = "0" + hh
        return hh + ":" + mm
    end if
    suffix = " AM"
    if h >= 12 then suffix = " PM"
    h12 = h mod 12
    if h12 = 0 then h12 = 12
    return h12.toStr() + ":" + mm + suffix
end function

' Epoch seconds -> "Today 7:30 PM", "Tomorrow 9:00 AM", "Sat 8:00 PM".
function dayAndTime(epoch as Dynamic, clock24 as Boolean, allDay as Boolean) as String
    now = CreateObject("roDateTime")
    now.toLocalTime()
    dt = CreateObject("roDateTime")
    dt.fromSeconds(Int(epoch))
    dt.toLocalTime()
    nowDays = Int((now.asSeconds() - now.getHours() * 3600 - now.getMinutes() * 60 - now.getSeconds()) / 86400)
    days = Int((dt.asSeconds() - dt.getHours() * 3600 - dt.getMinutes() * 60 - dt.getSeconds()) / 86400) - nowDays
    if days = 0
        label = "Today"
    else if days = 1
        label = "Tomorrow"
    else if days < 0
        label = "Earlier"
    else
        label = Left(dt.getWeekday(), 3)
    end if
    if allDay then return label
    return label + " " + hhmm(dt, clock24)
end function

' Seconds until something -> "in 3h 20m" / "in 12m" / "aired".
function untilText(secs as Dynamic) as String
    s = Int(secs)
    if s <= 0 then return "Out now"
    mins = Int(s / 60)
    if mins < 60 then return "in " + mins.toStr() + "m"
    hrs = Int(mins / 60)
    if hrs < 24 then return "in " + hrs.toStr() + "h " + (mins mod 60).toStr() + "m"
    return "in " + Int(hrs / 24).toStr() + "d " + (hrs mod 24).toStr() + "h"
end function

' "#D9894A" -> "0xD9894AFF" (with optional alpha hex "80")
function rokuColor(hexColor as Dynamic, alpha as String) as String
    if type(hexColor) <> "String" and type(hexColor) <> "roString" then return "0xD9894A" + alpha
    hc = hexColor
    if Left(hc, 1) = "#" then hc = hc.mid(1)
    if hc.len() <> 6 then return "0xD9894A" + alpha
    return "0x" + UCase(hc) + alpha
end function

' ---------------------------------------------------------------- color
' Hub's evening palette: warm ink panels, cream text. The accent comes from Hub settings.
function pal(name as String) as String
    if name = "ink" then return "0x14120FFF"
    if name = "panel" then return "0x1E1A16FF"
    if name = "raise" then return "0x2A251FFF"
    if name = "line" then return "0x3A332BFF"
    if name = "mist" then return "0xBDB3A3FF"
    if name = "dim" then return "0x8A8174FF"
    if name = "coral" then return "0xE07A5FFF"
    return "0xF2EADCFF" ' cream
end function

' ---------------------------------------------------------------- motion
' Every move in Hub follows a damped spring (mass 1). The closed-form solution is sampled
' into keyframes, so the Animation runs with a linear ease and the spring sets the shape.
'   snappy: focus and presses   smooth: rows and pages   gentle: fades   bouncy: arrivals
'   slow: photo crossfades
function springSpec(preset as String) as Object
    if m.springs = invalid then m.springs = {}
    if m.springs.doesExist(preset) then return m.springs[preset]
    k = 420.0
    c = 34.0
    if preset = "smooth"
        k = 220.0
        c = 28.0
    else if preset = "gentle"
        k = 120.0
        c = 22.0
    else if preset = "bouncy"
        k = 300.0
        c = 20.0
    else if preset = "slow"
        k = 40.0
        c = 12.6
    end if
    w0 = Sqr(k)
    z = c / (2.0 * w0)
    ' time to settle within about 0.3% of the target
    secs = 8.3 / w0
    if z < 0.9 then secs = 5.8 / (z * w0)
    keys = []
    vals = []
    n = 30
    for i = 0 to n
        t = secs * i / n
        if z < 1
            wd = w0 * Sqr(1.0 - z * z)
            x = 1.0 - Exp(-z * w0 * t) * (Cos(wd * t) + (z * w0 / wd) * Sin(wd * t))
        else
            x = 1.0 - Exp(-w0 * t) * (1.0 + w0 * t)
        end if
        keys.push(i / n)
        vals.push(x)
    end for
    vals[n] = 1.0
    spec = { keys: keys, vals: vals, secs: secs }
    m.springs[preset] = spec
    return spec
end function

' Fills `interp` with a spring from a to b (Float or [x, y]) and sets the animation's length.
' Start from the field's current value and an interrupted move carries on from where it is.
sub springTo(anim as Dynamic, interp as Object, a as Dynamic, b as Dynamic, preset as String)
    spec = springSpec(preset)
    vals = []
    vec = type(a) = "roArray"
    for each p in spec.vals
        if vec
            vals.push([a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p])
        else
            vals.push(a + (b - a) * p)
        end if
    end for
    interp.key = spec.keys
    interp.keyValue = vals
    if anim <> invalid
        anim.easeFunction = "linear"
        anim.duration = spec.secs
    end if
end sub

' Same, for opacity: the spring's overshoot is clipped to 0..1.
sub springFade(anim as Dynamic, interp as Object, a as Float, b as Float, preset as String)
    springTo(anim, interp, a, b, preset)
    vals = interp.keyValue
    out = []
    for each v in vals
        if v > 1 then v = 1.0
        if v < 0 then v = 0.0
        out.push(v)
    end for
    interp.keyValue = out
end sub

' A press: squeeze to 92% fast, then spring back to `s`.
sub springPress(anim as Object, interp as Object, s as Float)
    spec = springSpec("bouncy")
    keys = [0.0]
    vals = [[s, s]]
    lo = s * 0.92
    head = 0.07 / (spec.secs + 0.07)
    keys.push(head)
    vals.push([lo, lo])
    for i = 1 to spec.keys.count() - 1
        keys.push(head + spec.keys[i] * (1.0 - head))
        v = lo + (s - lo) * spec.vals[i]
        vals.push([v, v])
    end for
    interp.key = keys
    interp.keyValue = vals
    anim.easeFunction = "linear"
    anim.duration = spec.secs + 0.07
end sub

' Slow harmonic drift for ambient motion (wallpaper breathing): x = a + (b - a)(1 - cos 2πt) / 2.
sub driftLoop(anim as Object, interp as Object, a as Dynamic, b as Dynamic, secs as Float)
    keys = []
    vals = []
    n = 24
    vec = type(a) = "roArray"
    for i = 0 to n
        p = (1.0 - Cos(6.2831853 * i / n)) / 2.0
        keys.push(i / n)
        if vec
            vals.push([a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p])
        else
            vals.push(a + (b - a) * p)
        end if
    end for
    interp.key = keys
    interp.keyValue = vals
    anim.easeFunction = "linear"
    anim.duration = secs
    anim.repeat = true
end sub

' Fade in on a spring, hold for `hold` seconds, fade out on a spring (captions, notices).
sub springHold(anim as Object, interp as Object, hold as Float)
    spec = springSpec("gentle")
    t = spec.secs
    total = t * 2 + hold
    keys = []
    vals = []
    for i = 0 to spec.keys.count() - 1
        keys.push(spec.keys[i] * t / total)
        v = spec.vals[i]
        if v > 1 then v = 1.0
        vals.push(v)
    end for
    for i = 1 to spec.keys.count() - 1
        keys.push((t + hold + spec.keys[i] * t) / total)
        v = 1.0 - spec.vals[i]
        if v < 0 then v = 0.0
        vals.push(v)
    end for
    interp.key = keys
    interp.keyValue = vals
    anim.easeFunction = "linear"
    anim.duration = total
end sub
