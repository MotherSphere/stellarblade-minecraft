package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Stream=pcall(require,'capture_stream')
assert(loaded,'The bounded live capture lifecycle is missing')
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
local function fixture()
    local f={now=100,frames=0,clears=0,status='idle',pose={session=string.rep('a',32),state='entered',ack=1,position={x=1,y=2,z=3}}}
    local capture={stream_busy=function()return f.hd_owned end}
    function capture.status() return f.status end
    function capture.frame(link)
        f.frames=f.frames+1; f.link=link
        if f.frame_error then error('native export failed') end
        if f.frame_failure then return false,'native export failed' end
        if f.retirement_failure then f.status='cleanup-required' end
        if f.frame_time then f.now=f.frame_time end
        return true,'published'
    end
    function capture.clear()
        f.clears=f.clears+1
        assert(not f.stream.active(),'Stop must disable capture before cleanup')
        if f.clear_error then error('cleanup threw') end
        if f.clear_failure then f.status='cleanup-required'; return false,'cleanup failed' end
        f.status='idle'; return true,'cleared'
    end
    f.stream=Stream.new(capture,function() if f.pose_error then error('context changed') end; return f.pose end,100,function()return f.now end)
    return f
end
check('capture is opt-in and requires an entered link',function()
    local f=fixture(); f.stream.tick(); assert(f.frames==0 and not f.stream.active())
    f.pose=nil; assert(not f.stream.start()); assert(f.frames==0)
    f.pose={state='ready'}; assert(not f.stream.start()); assert(f.frames==0)
end)
check('start captures one linked generation per tick and explicit stop disables it',function()
    local f=fixture(); assert(f.stream.start()); assert(f.stream.active() and f.frames==0)
    assert(not f.stream.start(),'An active stream cannot be restarted to reset its limits')
    f.stream.tick(); f.pose.ack=2; f.stream.tick()
    assert(f.frames==2 and f.link.session==string.rep('a',32) and f.link.ack==2 and f.link.scale==100)
    assert(f.stream.stop()); f.stream.tick(); assert(f.frames==2 and not f.stream.active())
end)
check('lost, changed and invalid native contexts stop before another capture',function()
    for _,change in ipairs({'missing','ready','session','exception'}) do
        local f=fixture(); assert(f.stream.start()); f.stream.tick()
        if change=='missing' then f.pose=nil elseif change=='ready' then f.pose.state='ready'
        elseif change=='session' then f.pose.session=string.rep('b',32) else f.pose_error=true end
        f.stream.tick(); assert(not f.stream.active() and f.frames==1 and f.clears==2,change)
    end
end)
check('capture errors and failed retirement stop without automatic restart',function()
    for _,failure in ipairs({'frame_error','frame_failure','retirement_failure'}) do
        local f=fixture(); assert(f.stream.start()); f[failure]=true
        f.stream.tick(); assert(not f.stream.active() and f.frames==1 and f.clears==2)
        f[failure]=false; f.stream.tick(); assert(f.frames==1)
    end
end)
check('cleanup failures remain retryable while capture stays disabled',function()
    for _,failure in ipairs({'clear_error','clear_failure'}) do
        local f=fixture(); assert(f.stream.start()); f.stream.tick(); f[failure]=true
        assert(not f.stream.stop()); assert(not f.stream.active())
        f.stream.tick(); assert(f.frames==1 and f.clears==3)
        assert(not f.stream.start(),'Failed cleanup cannot authorize a new stream')
        f[failure]=false; f.stream.tick(); assert(f.frames==1 and not f.stream.active())
        assert(f.stream.start()); assert(f.stream.active())
    end
end)
check('a cancelled schedule defers native cleanup until the next game-thread tick',function()
    local f=fixture(); assert(f.stream.start()); f.stream.tick()
    f.stream.cancel('game-thread scheduling failed')
    assert(not f.stream.active() and f.clears==1)
    f.stream.tick(); assert(f.clears==2 and f.frames==1)
end)
check('the allocation-heavy diagnostic stops after 300 published frames',function()
    local f=fixture(); assert(f.stream.start())
    for _=1,299 do f.stream.tick() end
    local _,message=f.stream.tick()
    assert(f.frames==300 and not f.stream.active() and f.clears==2)
    assert(message:find('300 frames',1,true) and message:find('0 wall seconds',1,true))
    f.stream.tick(); assert(f.frames==300)
end)
check('the wall deadline and backwards clocks stop capture',function()
    for _,now in ipairs({160,99}) do
        local f=fixture(); assert(f.stream.start()); f.now=now
        local _,message=f.stream.tick(); assert(f.frames==0 and not f.stream.active())
        assert(message:find('0 frames',1,true))
        if now==160 then assert(message:find('60 wall seconds',1,true)) end
    end
    local f=fixture(); assert(f.stream.start()); f.now=159; f.frame_time=160
    f.stream.tick(); assert(f.frames==1 and not f.stream.active(),'A slow export must clear the expired stream immediately')
end)
check('legacy live cannot invalidate a competing HD session',function()
    local f=fixture(); f.hd_owned=true
    assert(not f.stream.start() and f.clears==0 and not f.stream.active())
end)
print(count..' live capture lifecycle checks passed.')
