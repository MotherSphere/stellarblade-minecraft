-- One owned, non-colliding texture probe. Unreal calls stay in the adapter.
local Probe={}
local function finite(value) return type(value)=='number' and value==value and math.abs(value)<1e12 end
function Probe.new(engine,directory)
    assert(type(directory)=='string' and directory~='','The private bridge directory is required.')
    local fixture
    local api={}
    function api.status() return not fixture and 'idle' or fixture.complete and 'ready' or 'cleanup-required' end
    function api.clear()
        if not fixture then return true,'No texture probe is active.' end
        fixture.complete=false
        if fixture.handle then
            local ok,why=pcall(engine.destroy,fixture.handle)
            if not ok then return false,'Texture probe cleanup must be retried: '..tostring(why) end
        end
        fixture=nil
        return true,'Removed the owned texture probe.'
    end
    function api.toggle()
        if fixture then return api.clear() end
        local ok,why=pcall(function()
            local origin=engine.snapshot()
            assert(finite(origin.pawn_id) and origin.pawn_id>0 and finite(origin.world_id) and origin.world_id>0
                and finite(origin.half_height) and origin.half_height>0 and finite(origin.rotation.Yaw)
                and finite(origin.position.X) and finite(origin.position.Y) and finite(origin.position.Z),
                'A valid playable pawn, world and capsule are required.')
            local yaw=math.rad(origin.rotation.Yaw)
            local center={X=origin.position.X+250*math.cos(yaw),Y=origin.position.Y+250*math.sin(yaw),
                Z=origin.position.Z-origin.half_height+50}
            fixture={origin=origin,complete=false}
            fixture.handle=assert(engine.spawn(origin,center),'The texture probe actor was not created.')
            local parent=engine.configure(fixture.handle,directory)
            fixture.complete=true
            return 'Created one non-colliding texture probe using '..tostring(parent)..'; inspect the stone pattern in the native view.'
        end)
        if ok then return true,why end
        local cleaned,cleanup_message=api.clear()
        return false,tostring(why)..(cleaned and '' or '; '..cleanup_message)
    end
    function api.tick()
        if not fixture then return true end
        if not fixture.complete then return api.clear() end
        local ok,same=pcall(function()
            local now=engine.snapshot()
            return now.pawn_id==fixture.origin.pawn_id and now.world_id==fixture.origin.world_id
                and engine.valid(fixture.handle)
        end)
        if not ok or not same then return api.clear() end
        return true
    end
    return api
end
return Probe
