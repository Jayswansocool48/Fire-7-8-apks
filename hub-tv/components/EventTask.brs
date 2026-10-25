sub init()
    m.top.functionName = "pollEvents"
end sub

sub pollEvents()
    since = 0
    port = CreateObject("roMessagePort")
    while true
        server = m.top.server
        if server = "" or server = invalid
            sleep(2000)
        else
            s = server
            if Instr(1, s, ":") = 0 then s = s + ":8765"
            xfer = CreateObject("roUrlTransfer")
            xfer.setMessagePort(port)
            xfer.setUrl("http://" + s + "/api/hub/events?wait=20&since=" + since.toStr())
            ok = false
            if xfer.asyncGetToString()
                msg = wait(28000, port)
                if type(msg) = "roUrlEvent" and msg.getResponseCode() = 200
                    data = ParseJson(msg.getString())
                    if data <> invalid
                        ok = true
                        since = data.seq
                        for each ev in data.events
                            m.top.event = ev
                        end for
                    end if
                else
                    xfer.asyncCancel()
                end if
            end if
            if m.top.connected <> ok then m.top.connected = ok
            if not ok then sleep(3000)
        end if
    end while
end sub
