-- Bounded diagnostic producer. Tick and cleanup run on the game thread.
-- Scheduling cancellation changes only Lua state; the next tick retries cleanup.
local Stream={}
function Stream.new(capture,pose,scale,clock)
    clock=clock or os.time
    local api={}
    local live,clearing,session,started,last_time,frames=false,false,nil,nil,0,0
    local reason='explicit stop'
    function api.active() return live end
    function api.cancel(message)
        live=false
        clearing=true
        session=nil
        reason=message or 'explicit stop'
    end
    local function clear()
        local ran,ok,message=pcall(capture.clear)
        clearing=not (ran and ok)
        return not clearing,ran and message or tostring(ok)
    end
    function api.stop(message)
        api.cancel(message)
        local ok,why=clear()
        local timed,now=pcall(clock)
        local elapsed=timed and started and math.max(0,now-started) or 0
        return ok,string.format('Native live capture stopped: %s; %d frames in %g wall seconds. %s',reason,frames,elapsed,tostring(why))
    end
    function api.start()
        if live then return false,'Native live capture is already active; stop it before restarting.' end
        local ran,current=pcall(pose)
        if not ran or not current or current.state~='entered' then
            return false,'Enter the active Minecraft link before starting native live capture.'
        end
        local ok,why=api.stop('preparing a new run')
        if not ok then return false,why end
        started=clock(); last_time=started; frames=0; session=current.session; live=true
        return true,'Native live capture enabled at up to 5 Hz; stops after 300 frames or 60 seconds.'
    end
    function api.tick()
        if not live then
            if clearing then
                local ok,why=clear()
                return ok,'Native live capture cleanup: '..tostring(why)
            end
            return true
        end
        local ran,ok,message=pcall(function()
            local now=clock()
            if now<last_time or now-started>=60 then return api.stop('wall-clock limit or clock change') end
            last_time=now
            local current=pose()
            if not current or current.state~='entered' or current.session~=session then
                return api.stop('linked scene expired or changed')
            end
            -- ponytail: short-lived handles bound this 300-frame probe; measure native GC
            -- and synchronous export cost before considering persistent capture handles.
            local captured,why=capture.frame({session=current.session,ack=current.ack,scale=scale,position=current.position})
            if not captured then return api.stop('frame failed: '..tostring(why)) end
            frames=frames+1
            if capture.status()~='idle' then return api.stop('frame cleanup is required') end
            now=clock()
            if frames>=300 or now<last_time or now-started>=60 then
                return api.stop('diagnostic limit reached')
            end
            last_time=now
            return true
        end)
        if not ran then return api.stop('capture or bridge error: '..tostring(ok)) end
        return ok,message
    end
    return api
end
return Stream
