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

function makeFont(bold as Boolean, size as Integer) as Object
    f = CreateObject("roSGNode", "Font")
    if bold
        f.uri = "font:BoldSystemFontFile"
    else
        f.uri = "font:SystemFontFile"
    end if
    f.size = size
    return f
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

' "#4F7CFF" -> "0x4F7CFFFF" (with optional alpha hex "80")
function rokuColor(hexColor as Dynamic, alpha as String) as String
    if type(hexColor) <> "String" and type(hexColor) <> "roString" then return "0x4F7CFF" + alpha
    hc = hexColor
    if Left(hc, 1) = "#" then hc = hc.mid(1)
    if hc.len() <> 6 then return "0x4F7CFF" + alpha
    return "0x" + UCase(hc) + alpha
end function
