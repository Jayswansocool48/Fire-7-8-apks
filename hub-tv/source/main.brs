' Hub 3 - a launcher that bundles several apps into one sideloaded channel.
' The phone/tablet/PC can open Hub (or a Hub app) through the TV's control protocol:
' launch params arrive in `args`, and as roInputEvent while Hub is already running.
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.show()

    inputObj = CreateObject("roInput")
    inputObj.setMessagePort(port)

    if type(args) = "roAssociativeArray" and args.app <> invalid then scene.launchParams = args

    while true
        msg = wait(0, port)
        msgType = type(msg)
        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if msgType = "roInputEvent"
            info = msg.getInfo()
            if info <> invalid and info.app <> invalid then scene.launchParams = info
        end if
    end while
end sub
