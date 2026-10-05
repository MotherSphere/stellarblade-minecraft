-- Owns at most one displayed scene and one hidden replacement/cleanup obligation.
local Protocol=require('scene_protocol')
local Scene={}
local function same_binding(a,b)
    if a.session~=b.session or a.dimension~=b.dimension then return false end
    for _,axis in ipairs({'x','y','z'}) do if a.origin[axis]~=b.origin[axis] then return false end end
    if a.bounds and b.bounds then for i=1,6 do if a.bounds[i]~=b.bounds[i] then return false end end end
    return true
end
local function copy_binding(b)
    return {session=b.session,dimension=b.dimension,origin={x=b.origin.x,y=b.origin.y,z=b.origin.z},bounds={table.unpack(b.bounds)}}
end
function Scene.new(engine,bridge,directory,scale,clock)
    assert(type(directory)=='string' and directory~='','The private scene directory is required.')
    assert(type(scale)=='number' and scale>0 and scale<1e12,'The scene scale must be finite and positive.')
    clock=clock or os.time
    local enabled,restore_pending=false,false
    local owned,active,binding,context,last_packet,failed_revision={},nil,nil,nil,nil,nil
    local waiting_since=0
    local api={}
    local function snapshot()
        local value=engine.snapshot()
        for _,number in ipairs({value.pawn_id,value.world_id,value.half_height,
            value.position.X,value.position.Y,value.position.Z}) do
            assert(type(number)=='number' and number==number and math.abs(number)<1e15,'Native scene context contains invalid coordinates.')
        end
        assert(value.pawn_id>0 and value.world_id>0 and value.half_height>0,'Native scene context is invalid.')
        return value
    end
    function api.enabled() return enabled end
    function api.status()
        if restore_pending or #owned>(active and 1 or 0) then return 'cleanup-required' end
        return enabled and (active and 'active' or 'waiting') or 'idle'
    end
    function api.read()
        local file,why=io.open(directory..'/native-scene.txt','rb')
        if not file then return nil,why end
        local text=file:read(1024*1024+1); file:close()
        return Protocol.parse(text or '')
    end
    local function cleanup_retired()
        local failures={}
        for i=#owned,1,-1 do
            local item=owned[i]
            if item~=active then
                local hidden,hide_error=pcall(engine.set_visible,item.handle,false)
                local removed,why=pcall(engine.destroy,item.handle)
                if removed then table.remove(owned,i)
                else failures[#failures+1]=tostring(why)..(hidden and '' or '; '..tostring(hide_error)) end
            end
        end
        return #failures==0,table.concat(failures,'; ')
    end
    local function restore_diagnostics()
        restore_pending=true
        local ran,ok,why=pcall(bridge.set_visuals_visible,true)
        if ran and ok then restore_pending=false; return true end
        return false,tostring(ran and why or ok)
    end
    function api.clear(reason)
        enabled=false; active=nil; binding=nil; context=nil; last_packet=nil; failed_revision=nil
        local cleaned,cleanup_error=cleanup_retired()
        local restored,restore_error=restore_diagnostics()
        local message='Native scene stopped'..(reason and ': '..reason or '')..'.'
        if not cleaned then message=message..' Scene cleanup required: '..cleanup_error end
        if not restored then message=message..' Diagnostic restoration required: '..restore_error end
        return cleaned and restored,message
    end
    function api.toggle()
        if enabled or #owned>0 or restore_pending then return api.clear() end
        local ok,why=pcall(function()
            local current=assert(bridge.binding(),'Enter the fresh Minecraft collision link before enabling native visuals.')
            assert(current.state=='entered','Enter the Minecraft collision link first.')
            context=snapshot(); binding=copy_binding(current)
            waiting_since=clock(); last_packet=nil; failed_revision=nil; enabled=true
        end)
        if not ok then context=nil; binding=nil; return false,tostring(why) end
        return true,'Native scene rendering enabled; waiting for matching Minecraft faces.'
    end
    local function discard_active(now)
        active=nil; waiting_since=now
        local cleaned,why=cleanup_retired()
        local restored,message=restore_diagnostics()
        return cleaned and restored,tostring(why)..(restored and '' or '; '..tostring(message))
    end
    local function build(packet,current,native)
        local anchor={X=native.position.X-current.position.x*scale,
            Y=native.position.Y-current.position.z*scale,
            Z=native.position.Z-native.half_height-current.position.y*scale}
        local next_scene={packet=packet,time=packet.time}
        local ok,why=pcall(function()
            next_scene.handle=assert(engine.spawn(native,anchor),'Native scene actor was not created.')
            owned[#owned+1]=next_scene
            engine.configure(next_scene.handle,packet,directory,scale)
            assert(Protocol.fresh(packet.time,clock()),'Native scene expired while its meshes were being built.')
            local latest=bridge.binding()
            assert(latest and same_binding(binding,latest),'Minecraft binding changed while building native meshes.')
            local matched,message=Protocol.validate_binding(packet,latest)
            assert(matched,message)
            if active then engine.set_visible(active.handle,false) end
            engine.set_visible(next_scene.handle,true)
            local hidden,message=bridge.set_visuals_visible(false)
            assert(hidden,message)
        end)
        if not ok then
            failed_revision=packet.revision
            cleanup_retired()
            local checked,restorable=pcall(function()
                if not active or not Protocol.fresh(active.time,clock()) then return false end
                local latest=bridge.binding()
                if not latest or not same_binding(binding,latest) then return false end
                local current_native=snapshot()
                return current_native.pawn_id==context.pawn_id and current_native.world_id==context.world_id
                    and engine.valid(active.handle) and Protocol.validate_binding(active.packet,latest)
            end)
            if checked and restorable then
                local shown,show_error=pcall(engine.set_visible,active.handle,true)
                if not shown then api.clear('previous scene could not be restored: '..tostring(show_error)) end
            else api.clear('scene creation failed') end
            return false,'Native scene replacement failed: '..tostring(why)
        end
        active=next_scene; restore_pending=false; failed_revision=nil
        local cleaned,cleanup_error=cleanup_retired()
        return cleaned,'Native scene revision '..packet.revision..' ready.'..(cleaned and '' or ' Old scene cleanup required: '..cleanup_error)
    end
    function api.tick(packet)
        if not enabled then
            if #owned>0 or restore_pending then return api.clear('retrying cleanup') end
            return true
        end
        local ran,ok,message=pcall(function()
            local now=clock()
            local current=bridge.binding()
            if not current or current.state~='entered' or not same_binding(binding,current) then
                return api.clear('Minecraft binding expired or changed')
            end
            local native=snapshot()
            if native.pawn_id~=context.pawn_id or native.world_id~=context.world_id then
                return api.clear('native pawn or world changed')
            end
            if active and (active.packet.collision_revision~=current.ack or not engine.valid(active.handle)) then
                discard_active(now)
            end
            local cleaned,cleanup_error=cleanup_retired()
            local restored,restore_error=true,nil
            if restore_pending then restored,restore_error=restore_diagnostics() end
            local candidate,invalid
            if packet and Protocol.fresh(packet.time,now) then
                if not same_binding(binding,packet) then return api.clear('visual descriptor binding changed') end
                local matched,why=Protocol.validate_binding(packet,current)
                if matched then
                    if last_packet and (packet.revision<last_packet.revision
                        or packet.revision==last_packet.revision and not Protocol.same_payload(packet,last_packet)) then
                        invalid='Visual revision changed without advancing, or moved backwards.'
                    else
                        last_packet=packet
                        if active and packet.revision==active.packet.revision then
                            active.time=math.max(active.time,packet.time)
                        elseif packet.revision~=failed_revision then candidate=packet end
                    end
                else invalid=why end
            end
            if candidate and cleaned and not restore_pending then return build(candidate,current,native) end
            if active and not Protocol.fresh(active.time,now) then return api.clear('visual heartbeat expired') end
            if not active and (now<waiting_since or now-waiting_since>5) then return api.clear('matching visual scene did not arrive') end
            if not cleaned then return false,'Native scene cleanup required: '..cleanup_error end
            if not restored then return false,'Diagnostic restoration required: '..tostring(restore_error) end
            if invalid then return false,tostring(invalid) end
            return true
        end)
        if not ran then
            local _,cleanup=api.clear('native scene error')
            return false,tostring(ok)..'; '..cleanup
        end
        return ok,message
    end
    return api
end
return Scene
