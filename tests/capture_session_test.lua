package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Session=pcall(require,'capture_session')
assert(loaded,'The continuous capture session controller is missing')
local run1,run2,session=string.rep('1',32),string.rep('2',32),string.rep('a',32)
local function request(run,time,state,identity)
    return string.format('SBMC_CAPTURE_SESSION 1\nRUN %s\nSESSION %s\nTIME %d\nSTATE %s\nEND\n',run,identity or session,time,state or 'active')
end
local function ack(run,id,identity)
    return string.format('SBMC_CAPTURE_ACK 1\nRUN %s\nSESSION %s\nID %s\nEND\n',run,identity or session,id)
end
local function packet(run,time,state,receipt)
    return {request=Session.parse_request(request(run,time,state)),ack=receipt and Session.parse_ack(receipt)}
end
assert(Session.parse_request(request(run1,100)).run==run1)
assert(Session.parse_ack(ack(run1,'100-1')).id=='100-1')
for _,invalid in ipairs({request(run1,100)..'x',request(run1,100):gsub('\n','\r\n'),request('A',100),request(run1,100,'unknown'),request(run1,100):gsub('100','inf'),string.rep('a',513)}) do
    assert(not Session.parse_request(invalid),'Malformed request was accepted')
end
assert(not Session.parse_ack(ack(run1,'../../image')) and not Session.parse_ack(ack('ABC','100-1')))
local function fixture()
    local f={now=100,frames=0,clears=0,enabled=false,allowed=true,pose={state='entered',session=session,ack=1,position={x=0,y=0,z=0}}}
    local c={}
    function c.stream_busy() return f.enabled or f.pending~=nil end
    function c.stream_pending() return f.pending end
    function c.stream_enable(value)
        if value and (f.pending or f.cleanup_failure or f.enable_failure) then return false,'ownership failed' end
        f.enabled=value; return true
    end
    function c.clear()
        f.clears=f.clears+1; f.enabled=false
        if f.cleanup_failure then return false,'cleanup failed' end
        return true
    end
    function c.stream_retry()
        f.retries=(f.retries or 0)+1
        if f.pending and f.pending.poisoned then return false,'poisoned' end
        if f.publish_failure then return false,'publication failed' end
        if f.pending then f.pending.published=true end
        return true
    end
    function c.stream_ack(run,identity,id)
        if f.pending and f.pending.run==run and f.pending.session==identity and f.pending.id==id and f.pending.published then
            f.pending=nil; f.acks=(f.acks or 0)+1
        end
        return true
    end
    function c.streamframe(link,run)
        assert(f.enabled and not f.pending,'A second native generation overlapped')
        f.frames=f.frames+1; f.link=link
        f.pending={run=run,session=link.session,id=f.now..'-'..f.frames,time=f.now,published=not f.publish_failure,poisoned=f.frame_failure}
        if f.frame_failure or f.publish_failure then return false,'frame failed' end
        return true
    end
    f.api=Session.new(c,function() if f.pose_failure then error('pose failed') end; return f.pose end,'/unused',100,function()return f.allowed end,function()return f.now end)
    return f
end
local f=fixture(); assert(f.api.tick({}) and f.frames==0)
assert(f.api.binding()==nil)
assert(f.api.tick(packet(run1,100)) and f.api.active() and f.frames==1)
assert(f.api.binding().run==run1 and f.api.binding().session==session)
assert(f.api.tick(packet(run1,100)) and f.frames==1)
local first=f.pending.id
assert(f.api.tick(packet(run1,100,nil,ack(run2,first))) and f.frames==1)
assert(f.api.tick(packet(run1,100,nil,ack(run1,first))) and f.frames==2)
assert(f.link.scale==100 and f.link.ack==1)
assert(f.api.tick(packet(run1,100,'stopped')) and not f.api.active() and f.pending)
assert(f.api.binding()==nil,'A pending image must not keep input permitted after stop')
local second=f.pending.id
assert(f.api.tick(packet(run1,100,nil,ack(run1,second))) and f.frames==2 and not f.pending,'Stopped RUN must not resurrect after late ACK')
assert(f.api.tick(packet(run2,100)) and f.frames==3)
assert(f.api.tick(packet(run1,100)) and not f.api.active() and f.frames==3,'A previously stopped RUN cannot replay')

for _,reason in ipairs({'stale','future','pose','context','allowed','clock','exception','timeout'}) do
    f=fixture(); assert(f.api.tick(packet(run1,100))); local pending=f.pending.id
    if reason=='stale' then f.now=104
    elseif reason=='future' then f.now=99
    elseif reason=='pose' then f.pose=nil
    elseif reason=='context' then f.pose.session=string.rep('b',32)
    elseif reason=='allowed' then f.allowed=false
    elseif reason=='clock' then f.now=99
    elseif reason=='exception' then f.pose_failure=true
    elseif reason=='timeout' then f.now=106 end
    f.api.tick(packet(run1,reason=='timeout' and 106 or 100))
    assert(not f.api.active() and f.pending and f.frames==1,reason)
    f.now=110; f.pose={state='entered',session=session,ack=1,position={x=0,y=0,z=0}}; f.allowed=true; f.pose_failure=false
    f.api.tick(packet(run1,110,nil,ack(run1,pending)))
    assert(f.frames==1 and not f.pending,'Late ACK must retire without reviving '..reason)
    f.api.tick(packet(run2,110)); assert(f.frames==2)
end
f=fixture(); f.publish_failure=true; f.api.tick(packet(run1,100)); assert(not f.api.active() and f.frames==1 and f.pending)
f.publish_failure=false; f.api.tick(packet(run1,100)); assert(f.pending.published and f.frames==1,'Metadata recovery must work after stop without another export')
f.api.tick(packet(run2,100)); assert(f.frames==1,'New RUN cannot leap over unresolved older output')
f.api.tick(packet(run2,100,nil,ack(run1,f.pending.id))); assert(f.frames==2)
f=fixture(); f.api.tick(packet(run1,100)); f.api.cancel('schedule failed'); assert(not f.api.active() and f.clears==0)
f.cleanup_failure=true; f.api.tick(packet(run1,100)); assert(f.clears==1 and f.frames==1)
f.cleanup_failure=false; f.api.tick(packet(run1,100)); assert(f.clears==2 and f.frames==1)
f.api.tick(packet(run1,100,nil,ack(run1,f.pending.id))); assert(not f.pending and f.frames==1)
f=fixture(); f.api.tick(packet(run1,100)); assert(f.api.stop('F11')); f.api.tick(packet(run1,100)); assert(f.frames==1 and not f.api.active())
f=fixture(); f.api.tick(packet(run1,100))
local open=io.open; io.open=function()error('filesystem unavailable')end
local ran,stopped=pcall(f.api.stop,'F11'); io.open=open
assert(ran and stopped and not f.api.active() and f.pending,'F11 cancellation must not depend on request-file access')
f=fixture(); f.pose=nil
for index=1,130 do f.api.tick(packet(string.format('%032x',index),100)) end
f.pose={state='entered',session=session,ack=1,position={x=0,y=0,z=0}}
assert(not f.api.tick(packet(string.rep('f',32),100)) and f.frames==0,'The bounded replay latch fails closed once full')
print('PASS continuous HD session framing, exact ACK, restart/stop latching, context, timeout, publication retry and deferred cleanup')
