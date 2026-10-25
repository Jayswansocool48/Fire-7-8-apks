sub init()
    m.top.functionName = "doRequest"
end sub

sub doRequest()
    xfer = CreateObject("roUrlTransfer")
    port = CreateObject("roMessagePort")
    xfer.setMessagePort(port)
    xfer.setUrl(m.top.url)
    xfer.retainBodyOnError(true)
    xfer.setCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.initClientCertificates()

    result = { ok: false, code: 0, body: "" }
    if m.top.method = "POST"
        xfer.addHeader("Content-Type", "application/json")
        started = xfer.asyncPostFromString(m.top.body)
    else
        started = xfer.asyncGetToString()
    end if

    if started
        msg = wait(m.top.timeoutMs, port)
        if type(msg) = "roUrlEvent"
            result.code = msg.getResponseCode()
            result.body = msg.getString()
            result.ok = (result.code >= 200 and result.code < 300)
        else
            xfer.asyncCancel()
            result.body = "timeout"
        end if
    end if
    m.top.response = result
end sub
