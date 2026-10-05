-- Minecraft-owned HD session. Native capture and cleanup run only on the game thread.
local Session={}
local function identity(value) return value and #value==32 and value:match('^[0-9a-f]+$') end
local function bounded(text) return type(text)=='string' and #text<=512 and not text:find('[^\n\32-\126]') end
function Session.parse_request(text)
    if not bounded(text) then return nil end
    local run,session,time,state=text:match('^SBMC_CAPTURE_SESSION 1\nRUN ([0-9a-f]+)\nSESSION ([0-9a-f]+)\nTIME (%d+)\nSTATE (%a+)\nEND\n$')
    time=tonumber(time)
    if not identity(run) or not identity(session) or not time or time>9007199254740991
        or (state~='active' and state~='stopped') then return nil end
    return {run=run,session=session,time=time,state=state}
end
function Session.parse_ack(text)
    if not bounded(text) then return nil end
    local run,session,id=text:match('^SBMC_CAPTURE_ACK 1\nRUN ([0-9a-f]+)\nSESSION ([0-9a-f]+)\nID (%d+%-%d+)\nEND\n$')
    if not identity(run) or not identity(session) or not id or #id>64 then return nil end
    return {run=run,session=session,id=id}
end
local function read(path,parse)
    local file=io.open(path,'rb')
    if not file then return nil end
    local text=file:read(513); file:close()
    return parse(text)
end
function Session.new(capture,pose,directory,scale,allowed,clock)
    clock=clock or os.time
    local api={}
    local live,clearing,run,session,last_time,frames=false,false,nil,nil,nil,0
    local stopped,stopped_count={},0
    local reason='explicit stop'
    local function latch(value)
        if value and not stopped[value] and stopped_count<128 then stopped[value]=true; stopped_count=stopped_count+1 end
    end
    function api.active() return live end
    function api.binding() return live and {run=run,session=session} or nil end
    function api.busy() return capture.stream_busy() end
    function api.read()
        return {request=read(directory..'/eve-stream.txt',Session.parse_request),
            ack=read(directory..'/eve-frame-ack.txt',Session.parse_ack)}
    end
    function api.cancel(message)
        live=false; clearing=true; latch(run)
        capture.stream_enable(false)
        reason=message or 'explicit stop'
    end
    local function cleanup()
        local ran,ok,message=pcall(capture.clear)
        clearing=not (ran and ok)
        return not clearing,ran and message or tostring(ok)
    end
    local function stop_current(message)
        api.cancel(message)
        local ok,why=cleanup()
        return ok,string.format('Continuous HD stopped: %s; %d frames.%s',reason,frames,
            ok and '' or ' '..tostring(why))
    end
    function api.stop(message)
        -- F11 must also latch a request that arrived before its first scheduled tick.
        api.cancel(message)
        local read_ok,request=pcall(read,directory..'/eve-stream.txt',Session.parse_request)
        if read_ok and request then latch(request.run) end
        return stop_current(message)
    end
    local function tick(packet)
        if packet.ack then
            local ack=packet.ack
            local ok,why=capture.stream_ack(ack.run,ack.session,ack.id)
            if not ok then return stop_current('ACK cleanup failed: '..tostring(why)) end
        end
        if clearing then
            local ok,why=cleanup()
            if not ok then return false,'Continuous HD cleanup: '..tostring(why) end
        end
        local pending=capture.stream_pending()
        if pending and not pending.published then
            local ok,why=capture.stream_retry()
            if not ok then
                if live then return stop_current(tostring(why)) end
                return false,why
            end
        end
        local now=clock()
        assert(type(now)=='number' and now>=0 and now<9007199254740992 and now%1==0,'Invalid session clock.')
        local backwards=last_time and now<last_time; last_time=now
        if live and backwards then return stop_current('wall clock moved backwards') end
        if pending and now-pending.time>=5 then
            latch(pending.run)
            if live then return stop_current('pending frame ACK timed out') end
        end
        local request=packet.request
        if not request or request.state~='active' or now<request.time or now-request.time>3 then
            if request and request.state=='stopped' then latch(request.run) end
            if live then return stop_current('Minecraft request stopped, expired or invalid') end
            return true
        end
        if live and (request.run~=run or request.session~=session) then
            return stop_current('Minecraft capture run changed')
        end
        if not live and (stopped[request.run] or pending) then return true end
        local current=pose()
        if not allowed() or not current or current.state~='entered' or current.session~=request.session then
            if not live then run=request.run end
            return stop_current('entered terrain context or capture ownership is unavailable')
        end
        local started=false
        if not live then
            -- ponytail: retain at most 128 stopped run IDs; restart the game if a session exceeds this bound.
            if stopped_count>=128 then return false,'Continuous HD run limit reached; restart the game.' end
            run=request.run; session=request.session; frames=0
            local ok,why=capture.stream_enable(true)
            if not ok then return stop_current(tostring(why)) end
            live=true; started=true
        end
        if pending then return true end
        local ok,why=capture.streamframe({session=session,ack=current.ack,scale=scale,position=current.position},run)
        if not ok then return stop_current('frame failed: '..tostring(why)) end
        frames=frames+1
        return true,started and 'Continuous HD started; one pending frame, stopped by Minecraft or F11.' or nil
    end
    function api.tick(packet)
        local ran,ok,message=pcall(tick,packet or {})
        if not ran then return stop_current('capture session error: '..tostring(ok)) end
        return ok,message
    end
    return api
end
return Session
