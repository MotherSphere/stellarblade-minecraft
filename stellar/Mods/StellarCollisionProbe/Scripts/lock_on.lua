-- Minecraft's lock-on for EVE (eve-lock.txt, SBMC_LOCK 1). While Minecraft holds a target, EVE is locked onto a hidden,
-- inert SBCharacter kept at the target, so Stellar Blade's own lock-on faces and strafes her (its movement rotation faces
-- the lock-on direction, her AnimBP enters its lock-on locomotion on IsLockOn). Its lock-on camera stays off, since
-- Minecraft owns the camera, and its off-screen timer is cleared each frame; if it still drops the lock, the next try
-- comes at once, then at most every RETRY seconds. Minecraft's release, a stale or foreign file, link loss or an error
-- undoes everything, retried every frame until done; spawn, follow and engage errors together stop new locks after
-- SPAWN_TRIES, so a broken proxy is never respawned frame after frame. Routine lines stop at ROUTINE_LINES, keeping
-- the rest of the log budget for releases and failures. Game-thread only; call tick each native frame.
local Lock={}
local FRESH_MS,LEASE,RETRY,REPORT,SPAWN_TRIES,ROUTINE_LINES=2500,0.6,0.25,1,3,200
-- While locked, EVE's sword is drawn as Stellar Blade's lock-on draws it, checked every DRAW_WAIT seconds (its draw Show
-- attaches the sword 0.337 s in). A draw that did not stick by the next check is retried with the attach event sent at
-- once, DRAWS tries in a row at most; release sheathes it if we drew.
local DRAW_WAIT,DRAWS=1,5
function Lock.parse(text)
    if type(text)~='string' or #text>256 then return nil end
    local session,seq,time,target,ending=text:match('^SBMC_LOCK 1\nSESSION (%x+)\nSEQ (%d+)\nTIME (%d+)\nTARGET ([^\n]+)\nEND (%d+)\n$')
    if not session or #session~=32 or session:find('[^0-9a-f]') or seq~=ending then return nil end
    local at
    if target~='none' then
        local x,y,z=target:match('^(%S+) (%S+) (%S+)$')
        x,y,z=tonumber(x),tonumber(y),tonumber(z)
        if not (x and y and z) or x~=x or y~=y or z~=z or math.abs(x)>4096 or math.abs(y)>4096 or math.abs(z)>4096 then return nil end
        at={x=x,y=y,z=z}
    end
    return {session=session,seq=tonumber(seq),time=tonumber(time),target=at}
end
-- wall: Unix seconds; real: monotonic seconds; link: the entered link ({session=,anchor=,scale=}) or nil.
function Lock.new(engine,directory,wall,real,log,link)
    local api,lines,swept,spawn_failures={},0,false,0
    local last,seen -- The newest accepted publication, and when its sequence arrived.
    local held -- {proxy=,camera=,engaged=,retry=,relocks=,report=,draw=,draws=,drew=} while EVE is locked for Minecraft.
    local undo -- A lock whose release failed, retried first every frame.
    local function note(message) if lines<300 then lines=lines+1; log('Lock: '..message) end end
    local function status(proxy)
        local ok,line=pcall(engine.lock_status,proxy)
        return ok and line or 'status failed: '..tostring(line)
    end
    local function read()
        local f=io.open(directory..'/eve-lock.txt','rb')
        if not f then return nil end
        local text=f:read(257); f:close()
        return text
    end
    function api.release(why)
        undo,held=held or undo,nil
        if not undo then return true,'Lock-on idle.' end
        if undo.drew then -- Once, before the release that may be retried.
            undo.drew=false
            local _,how=pcall(engine.lock_sheathe)
            note('sword: '..tostring(how))
        end
        local ok,how=pcall(engine.lock_release,undo.proxy,undo.camera)
        if not ok then
            if not undo.failed then undo.failed=true; note('release failed, retrying: '..tostring(how)) end
            return false,'Lock-on release failed: '..tostring(how)
        end
        undo=nil
        note('released ('..why..'): '..how)
        return true,'Lock-on released.'
    end
    function api.tick()
        if not swept then -- Leftovers of a Lua reload.
            swept=true
            local ok,count=pcall(engine.lock_sweep)
            if not ok or count>0 then note('startup sweep: '..tostring(count)) end
        end
        if undo and not api.release('retry') then return end
        local place,now=link(),real()
        local event=place and Lock.parse(read())
        if event and event.session==place.session then
            local same=last and last.session==event.session
            if same and event.seq>last.seq or not same and math.abs(wall()*1000-event.time)<=FRESH_MS then last,seen=event,now end
        end
        local target=place and last and last.session==place.session and now-seen<=LEASE and last.target
        if not target then
            if held then api.release(not place and 'link lost' or now-seen>LEASE and 'stale' or 'Minecraft released') end
            return
        end
        local a,s=place.anchor,place.scale
        local at={X=a.X+target.x*s,Y=a.Y+target.z*s,Z=a.Z+target.y*s} -- Minecraft (X, Y, Z) to Unreal (X, Z, Y).
        if not held then
            if spawn_failures>=SPAWN_TRIES then return end
            local ok,proxy,info=pcall(engine.lock_spawn,at)
            if not ok then
                spawn_failures=spawn_failures+1
                note(string.format('proxy failed (%d/%d): %s',spawn_failures,SPAWN_TRIES,tostring(proxy)))
                return
            end
            held={proxy=proxy,engaged=false,retry=-math.huge,relocks=0,report=now,draw=-math.huge,draws=0,drew=false}
            note(info)
            local saved,previous=pcall(engine.lock_camera,false)
            if saved then held.camera=previous else note('camera flag unavailable: '..tostring(previous)) end
        end
        local ok,locked=pcall(engine.lock_follow,held.proxy,at)
        if not ok then spawn_failures=spawn_failures+1; api.release('error: '..tostring(locked)); return end
        if not locked and now>=held.retry then
            held.retry=now+RETRY
            if held.engaged then
                held.relocks=held.relocks+1
                if lines<ROUTINE_LINES then note('lost '..held.relocks..': '..status(held.proxy)) end
            end
            local engaged,result=pcall(engine.lock_engage,held.proxy,at)
            if not engaged then spawn_failures=spawn_failures+1; api.release('error: '..tostring(result)); return end
            if result and not held.engaged then note('engaged: '..status(held.proxy)) end
            held.engaged=held.engaged or result
        end
        if locked and now>=held.draw and held.draws<DRAWS then
            held.draw=now+DRAW_WAIT
            local ok,drew=pcall(engine.lock_draw,held.draws>0)
            if ok and not drew then held.draws=0 -- Drawn already, or Stellar Blade's.
            else
                held.draws,held.drew=held.draws+1,held.drew or ok
                if lines<ROUTINE_LINES then note(string.format('sword %d/%d: %s',held.draws,DRAWS,tostring(drew))) end
            end
        end
        if now-held.report>=REPORT and lines<ROUTINE_LINES then
            held.report=now
            note(string.format('held, %d relocks: %s',held.relocks,status(held.proxy)))
        end
    end
    return api
end
return Lock
