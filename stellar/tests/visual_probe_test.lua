package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Probe=pcall(require,'visual_probe')
assert(loaded,'The owned texture-probe lifecycle is missing')
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
local function fixture()
    local f={spawned=0,configured=0,destroyed=0,live=0,pawn=11,world=12,yaw=90,valid=true}
    local engine={}
    function engine.snapshot()
        if f.snapshot_error then error('pawn unavailable') end
        return {pawn_id=f.pawn,world_id=f.world,position={X=10,Y=20,Z=300},rotation={Yaw=f.yaw},half_height=95}
    end
    function engine.spawn(origin,center)
        f.spawned=f.spawned+1; f.live=f.live+1; f.center=center
        return {actor={}}
    end
    function engine.configure(handle,directory)
        assert(directory=='/private/bridge' and handle.actor)
        f.configured=f.configured+1
        if f.configure_error then error('material or import failed') end
        return 'opaque test parent'
    end
    function engine.valid() return f.valid end
    function engine.destroy(handle)
        assert(handle.actor)
        if f.destroy_error then error('destroy failed') end
        f.destroyed=f.destroyed+1; f.live=f.live-1; handle.actor=nil
    end
    f.probe=Probe.new(engine,'/private/bridge')
    return f
end
check('one opt-in cube is placed ahead of the pawn with its bottom at the feet',function()
    local f=fixture(); assert(f.probe.status()=='idle'); f.probe.tick(); assert(f.spawned==0)
    assert(f.probe.toggle()); assert(f.probe.status()=='ready' and f.live==1)
    assert(math.abs(f.center.X-10)<1e-8 and math.abs(f.center.Y-270)<1e-8 and f.center.Z==255)
    for _=1,100 do f.probe.tick() end
    assert(f.spawned==1 and f.configured==1,'Ticks must not create actors or load textures')
    assert(f.probe.toggle()); assert(f.probe.status()=='idle' and f.live==0)
    assert(f.probe.clear() and f.destroyed==1,'Cleanup must be idempotent')
end)
check('failed setup immediately destroys the already-owned actor',function()
    local f=fixture(); f.configure_error=true
    assert(not f.probe.toggle()); assert(f.live==0 and f.destroyed==1 and f.probe.status()=='idle')
end)
check('failed destruction retains ownership and blocks new allocations until retry',function()
    local f=fixture(); f.configure_error=true; f.destroy_error=true
    assert(not f.probe.toggle()); assert(f.live==1 and f.probe.status()=='cleanup-required')
    assert(not f.probe.toggle()); f.probe.tick(); assert(f.spawned==1)
    f.destroy_error=false; f.probe.tick(); assert(f.live==0 and f.probe.status()=='idle')
    f.probe.tick(); assert(f.spawned==1,'Successful cleanup must not restart the probe')
end)
check('explicit failed cleanup stays disabled and is retried by the next tick',function()
    local f=fixture(); assert(f.probe.toggle()); f.destroy_error=true
    assert(not f.probe.clear()); assert(f.probe.status()=='cleanup-required')
    f.destroy_error=false; f.probe.tick(); assert(f.live==0 and f.destroyed==1)
end)
check('pawn, world, missing player and missing actor each clear the probe',function()
    for _,change in ipairs({'pawn','world','missing','actor'}) do
        local f=fixture(); assert(f.probe.toggle())
        if change=='pawn' then f.pawn=99 elseif change=='world' then f.world=99
        elseif change=='missing' then f.snapshot_error=true else f.valid=false end
        f.probe.tick(); assert(f.probe.status()=='idle' and f.live==0,change)
    end
end)
check('invalid context values fail before native allocation',function()
    for _,change in ipairs({'yaw','pawn','missing'}) do
        local f=fixture()
        if change=='yaw' then f.yaw=0/0 elseif change=='pawn' then f.pawn=0 else f.snapshot_error=true end
        assert(not f.probe.toggle()); assert(f.spawned==0 and f.probe.status()=='idle')
    end
end)
print(count..' visual probe lifecycle checks passed.')
