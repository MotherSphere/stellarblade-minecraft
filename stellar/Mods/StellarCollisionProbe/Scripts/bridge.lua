-- Local snapshots only. This module never executes packet contents.
local Protocol=require('bridge_protocol')
local Bridge={}
function Bridge.new(terrain,directory,log,clock)
    clock=clock or os.time
    local current,sequence,last_message,alive_time,stopping=nil,0,nil,0,false
    local parsed={}
    local api={status=terrain.status,shapes=terrain.shapes,set_visuals_visible=terrain.set_visuals_visible}
    function api.pose()
        if not current or stopping or not Protocol.fresh(alive_time,clock()) then return nil end
        return terrain.pose()
    end
    function api.binding()
        local pose=api.pose()
        if not pose or pose.state~='entered' then return nil end
        return {session=pose.session,dimension=current.dimension,state=pose.state,ack=pose.ack,
            origin={x=current.origin.x,y=current.origin.y,z=current.origin.z},
            bounds={table.unpack(current.bounds)},
            position={x=pose.position.x,y=pose.position.y,z=pose.position.z}}
    end
    local function report(message)
        if message~=last_message then log(message); last_message=message end
    end
    function api.read()
        local f,err=io.open(directory..'/terrain.txt','rb')
        if not f then return nil,err end
        local text=f:read(65537); f:close()
        return Protocol.parse_terrain_cached(text or '',parsed)
    end
    -- The window (bounds) follows EVE within one binding; the session, dimension and origin never move.
    local function same_binding(packet)
        if packet.session~=current.session or packet.dimension~=current.dimension then return false end
        for _,axis in ipairs({'x','y','z'}) do
            if packet.origin[axis]~=current.origin[axis] then return false end
        end
        return true
    end
    local function publish(pose,state)
        if not pose then return end
        local next_sequence=sequence+1
        pose={session=pose.session,seq=next_sequence,time=clock(),state=state or pose.state,
            ack=pose.ack,position=pose.position}
        local text=Protocol.encode_eve(pose)
        local temporary=directory..'/eve.tmp'
        local slot=directory..'/eve.'..tostring(next_sequence%2)
        local file,err=io.open(temporary,'wb')
        assert(file,err)
        local written,why=file:write(text)
        local closed,close_error=file:close()
        assert(written,why); assert(closed,close_error)
        -- MSVC rename refuses an existing destination. Keep the other slot intact.
        local exists=io.open(slot,'rb')
        if exists then exists:close(); assert(os.remove(slot)) end
        assert(os.rename(temporary,slot))
        sequence=next_sequence
    end
    local function safe_publish(pose,state)
        local ok,err=pcall(publish,pose,state)
        if not ok then report('Pose publication failed: '..tostring(err)) end
    end
    function api.create()
        if terrain.status()~='idle' then return false,'Return and clean up the active terrain first.' end
        local packet,err=api.read()
        if not packet then return false,'No valid Minecraft terrain: '..tostring(err) end
        if not Protocol.fresh(packet.time,clock()) then return false,'Minecraft terrain expired; run /stellar start in Minecraft.' end
        local ok,message=terrain.create(packet)
        if ok then
            current=packet; sequence=0; last_message=nil; alive_time=packet.time; stopping=false
            -- Recover the sequence even after restarting Stellar Blade within the same MC session.
            for slot=0,1 do
                local f=io.open(directory..'/eve.'..slot,'rb')
                if f then
                    local previous=Protocol.parse_eve(f:read(65537) or ''); f:close()
                    if previous and previous.session==packet.session then
                        sequence=math.max(sequence,previous.seq)
                    end
                end
            end
            safe_publish(terrain.pose())
        end
        return ok,message
    end
    function api.enter()
        if not current or stopping or not Protocol.fresh(alive_time,clock()) then
            return false,'Minecraft terrain expired; restore and relink.'
        end
        local ok,message=terrain.enter()
        if ok then safe_publish(terrain.pose()) end
        return ok,message
    end
    function api.restore()
        stopping=true
        local pose=terrain.pose()
        -- Suspend Minecraft authority before attempting a potentially fallible return.
        safe_publish(pose,'idle')
        local ok,message=terrain.restore()
        if ok then current=nil end
        return ok,message
    end
    function api.tick(packet)
        if not current then return end
        if stopping then -- A retried return also logs how it ended (a return from a lab site waits for the ground).
            local _,message=api.restore(); report(message)
            return
        end
        local now=clock()
        if packet and Protocol.fresh(packet.time,now) then
            if not same_binding(packet) or packet.seq<current.seq then
                report('Minecraft session changed; returning EVE before relinking.')
                local ok,message=api.restore(); if not ok then report(message) end
                return
            end
            alive_time=packet.time
            local ok,message=terrain.update(packet)
            if ok then
                if packet.seq~=current.seq then report(message) else last_message=nil end
                current=packet
            else
                -- Keep the imported ack, but a validated heartbeat still proves the peer is alive.
                report(message)
            end
        end
        if not Protocol.fresh(alive_time,now) then
            report('Minecraft terrain expired; attempting guarded return.')
            local ok,message=api.restore(); if not ok then report(message) end
            return
        end
        local pose,err=terrain.pose()
        if pose then safe_publish(pose) else report('Pose unavailable: '..tostring(err)) end
    end
    -- Each native frame: continues a terrain revision's import within the frame budget and reports how it ended, each
    -- distinct message once (heartbeats reset report's own repeat filter).
    local last_step
    function api.step()
        if not current or stopping then return end
        local _,message=terrain.step()
        if message and message~=last_step then last_step=message; report(message) end
    end
    return api
end
return Bridge
