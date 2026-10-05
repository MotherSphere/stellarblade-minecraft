package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Scene=pcall(require,'native_scene')
assert(loaded,'The native scene lifecycle is missing')
local SESSION=string.rep('a',32)
local function packet(revision,time,ack,digest)
    return {session=SESSION,revision=revision or 1,time=time or 100,collision_revision=ack or 1,
        dimension='minecraft:overworld',origin={x=0.5,y=64,z=0.5},
        textures={{index=0,file='mc-scene-'..SESSION..'-'..string.rep(digest or 'b',64)..'.png',size=100,width=256,height=256}},
        faces={{texture=0,center={x=0,y=0,z=0},direction='U'}}}
end
local function fixture()
    local f={now=100,spawned=0,configured=0,live={},events={},diagnostics=true,pawn=11,world=12}
    f.binding={session=SESSION,dimension='minecraft:overworld',origin={x=0.5,y=64,z=0.5},
        bounds={-4.5,-2,-4.5,4.5,4,4.5},state='entered',ack=1,position={x=2,y=3,z=4}}
    local engine={}
    function engine.snapshot() return {pawn_id=f.pawn,world_id=f.world,position={X=f.bad_position and 0/0 or 10,Y=20,Z=1000},half_height=95} end
    function engine.spawn(_,anchor)
        assert(anchor.X==-190 and anchor.Y==-380 and anchor.Z==605,'Native anchor must use the positive coordinate basis')
        local live=0; for _ in pairs(f.live) do live=live+1 end; assert(live<2,'Only active plus one replacement may exist')
        f.spawned=f.spawned+1; local h={id=f.spawned,visible=false}; f.live[h.id]=h; return h
    end
    function engine.configure(h,p)
        assert(not h.visible,'Replacement must be hidden during all configuration')
        f.configured=f.configured+1; f.events[#f.events+1]={'configure',h.id}
        if f.fail_configure then error('mesh marshaling failed') end
        if f.configure_time then f.now=f.configure_time end
        if f.after_configure then f.after_configure() end
    end
    function engine.valid(h) return f.live[h.id]~=nil end
    function engine.set_visible(h,visible)
        if f.fail_show and visible then error('show failed') end
        h.visible=visible; f.events[#f.events+1]={visible and 'show' or 'hide',h.id}
    end
    function engine.destroy(h)
        if f.fail_destroy==h.id then error('destroy failed') end
        f.live[h.id]=nil; f.events[#f.events+1]={'destroy',h.id}
    end
    local bridge={binding=function()if f.lost then return nil end; return f.binding end}
    function bridge.set_visuals_visible(visible)
        if f.fail_diagnostics then return false,'diagnostic visibility failed' end
        f.diagnostics=visible; return true,'visibility updated'
    end
    f.scene=Scene.new(engine,bridge,'/private/bridge',100,function()return f.now end)
    return f
end
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
check('opt-in scene uses one hidden replacement and skips unchanged heartbeats',function()
    local f=fixture(); f.scene.tick(packet()); assert(f.spawned==0 and not f.scene.enabled())
    assert(f.scene.toggle()); assert(f.scene.enabled() and f.spawned==0)
    assert(f.scene.tick(packet())); assert(f.live[1].visible and not f.diagnostics)
    f.now=101; assert(f.scene.tick(packet(1,101))); assert(f.spawned==1 and f.configured==1)
    assert(f.scene.tick(packet(2,101,1,'c'))); assert(not f.live[1] and f.live[2].visible and f.configured==2)
    assert(f.events[3][1]=='configure' and f.events[3][2]==2,'New mesh must be configured before swapping the old scene')
    assert(f.scene.clear()); assert(not next(f.live) and f.diagnostics and not f.scene.enabled())
end)
check('same-revision changes and rollbacks cannot renew a frozen scene',function()
    for _,mutation in ipairs({'payload','lower'}) do
        local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet(2)))
        f.now=104
        local p=packet(mutation=='lower' and 1 or 2,104,1,mutation=='payload' and 'c' or 'b')
        f.scene.tick(p); assert(f.spawned==1 and f.live[1].visible)
        f.now=106; p.time=106; f.scene.tick(p)
        assert(not f.scene.enabled() and not next(f.live) and f.diagnostics,mutation)
    end
end)
check('missing reads expire and context changes clear the active scene',function()
    for _,mutation in ipairs({'expired','pawn','world','session','origin','dimension','lost'}) do
        local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
        if mutation=='expired' then f.now=106 elseif mutation=='pawn' then f.pawn=99 elseif mutation=='world' then f.world=99
        elseif mutation=='session' then f.binding.session=string.rep('c',32) elseif mutation=='origin' then f.binding.origin.x=1.5
        elseif mutation=='dimension' then f.binding.dimension='minecraft:the_nether' else f.lost=true end
        f.scene.tick(nil); assert(not f.scene.enabled() and not next(f.live) and f.diagnostics,mutation)
    end
end)
check('collision acknowledgement changes hide old visuals until the matching scene arrives',function()
    local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
    f.binding.ack=2; f.scene.tick(packet(1,101,1))
    assert(f.scene.enabled() and not next(f.live) and f.diagnostics)
    assert(f.scene.tick(packet(2,101,2))); assert(f.live[2].visible and not f.diagnostics)
end)
check('failed replacement preserves matching old scene and does not reimport the failed generation',function()
    local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
    f.fail_configure=true; assert(not f.scene.tick(packet(2,101,1,'c')))
    assert(f.live[1].visible and not f.live[2] and not f.diagnostics)
    f.scene.tick(packet(2,102,1,'c')); assert(f.spawned==2 and f.configured==2)
    f.fail_configure=false; assert(f.scene.tick(packet(3,102,1,'d'))); assert(f.live[3].visible and not f.live[1])
end)
check('failed old-scene destruction blocks allocation while keeping the complete replacement',function()
    local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
    f.fail_destroy=1; f.scene.tick(packet(2,101,1,'c'))
    assert(f.live[1] and not f.live[1].visible and f.live[2].visible)
    f.scene.tick(packet(3,102,1,'d')); assert(f.spawned==2)
    f.fail_destroy=nil; f.scene.tick(packet(3,102,1,'d')); assert(f.spawned==3 and f.live[3].visible)
end)
check('failed initial setup retains cleanup obligations and never resumes automatically',function()
    local f=fixture(); assert(f.scene.toggle()); f.fail_configure=true; f.fail_destroy=1
    assert(not f.scene.tick(packet())); assert(not f.scene.enabled() and f.scene.status()=='cleanup-required')
    f.scene.toggle(); f.scene.tick(packet()); assert(f.spawned==1)
    f.fail_destroy=nil; f.scene.tick(nil); assert(not next(f.live) and not f.scene.enabled() and f.diagnostics)
end)
check('diagnostic restoration failure is retried even after native actors are gone',function()
    local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
    f.fail_diagnostics=true; assert(not f.scene.clear()); assert(not f.scene.enabled() and not next(f.live))
    assert(f.scene.status()=='cleanup-required')
    f.fail_diagnostics=false; f.scene.tick(nil); assert(f.diagnostics and f.scene.status()=='idle')
end)
check('collision transitions retry diagnostic restoration before accepting a replacement',function()
    local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
    f.binding.ack=2; f.fail_diagnostics=true; f.scene.tick(nil)
    assert(f.scene.enabled() and not next(f.live) and f.scene.status()=='cleanup-required')
    f.fail_diagnostics=false; f.scene.tick(packet(2,101,2))
    assert(f.spawned==2 and f.live[2].visible and not f.diagnostics)
end)
check('slow construction cannot show an expired scene and invalid native coordinates never allocate',function()
    local f=fixture(); assert(f.scene.toggle()); f.configure_time=106
    assert(not f.scene.tick(packet())); assert(not next(f.live) and not f.scene.enabled() and f.diagnostics)
    f=fixture(); f.bad_position=true
    assert(not f.scene.toggle()); assert(f.spawned==0 and not f.scene.enabled())
end)
check('rollback never re-shows a scene whose binding or native context changed during construction',function()
    for _,change in ipairs({'lost','ack','origin','bounds','ready','pawn','world'}) do
        local f=fixture(); assert(f.scene.toggle()); assert(f.scene.tick(packet()))
        local previous=f.live[1]
        f.fail_destroy=1 -- Even a retained old actor must stay hidden until cleanup can finish.
        f.after_configure=function()
            if change=='lost' then f.lost=true
            elseif change=='ack' then f.binding.ack=2
            elseif change=='origin' then f.binding.origin.x=1.5
            elseif change=='bounds' then f.binding.bounds[4]=5.5
            elseif change=='ready' then f.binding.state='ready'
            elseif change=='pawn' then f.pawn=99; error('mesh failed after pawn change')
            else f.world=99; error('mesh failed after world change') end
        end
        local event_count=#f.events
        assert(not f.scene.tick(packet(2,101,1,'c')))
        assert(not f.scene.enabled() and not previous.visible and f.diagnostics,change)
        for i=event_count+1,#f.events do
            assert(not (f.events[i][1]=='show' and f.events[i][2]==1),'Invalid old scene was re-shown after '..change)
        end
        f.fail_destroy=nil; f.scene.tick(nil); assert(not next(f.live) and f.scene.status()=='idle')
    end
end)
print(count..' native scene lifecycle checks passed.')
