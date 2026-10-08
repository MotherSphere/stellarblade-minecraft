package.path = 'stellar/Mods/StellarCollisionProbe/Scripts/?.lua;' .. package.path
local Terrain = require('terrain')
local tests = 0
local function check(name, run)
    run()
    tests = tests + 1
    print('PASS ' .. name)
end
local function backend()
    local b = {live={},next_id=0,pawn_id=1,world_id=2,
        position={X=10,Y=20,Z=100},moves=0,events={},clock=0,spawn_cost=0,spawns=0}
    function b.time() return b.clock end
    function b.snapshot()
        return {pawn_id=b.pawn_id,world_id=b.world_id,
            position={X=b.position.X,Y=b.position.Y,Z=b.position.Z},
            rotation={Pitch=0,Yaw=0,Roll=0},half_height=90,radius=35}
    end
    function b.spawn(spec)
        b.clock=b.clock+b.spawn_cost -- Seconds each spawn takes, for the frame budget.
        b.next_id=b.next_id+1
        if b.fail_spawn==b.next_id then error('spawn failed') end
        local a={id=b.next_id,spec=spec}
        b.live[a.id]=a
        b.spawns=b.spawns+1
        b.events[#b.events+1]={'spawn',a.id}
        return a
    end
    -- A new or parked actor takes its spec's place, size and collision; b.inside counts any made solid around EVE.
    function b.configure(a,spec)
        b.events[#b.events+1]={'configure',a.id}
        if b.fail_configure==a.id then error('configure failed') end
        if Terrain.overlaps(spec,b.position,35,90) then b.inside=(b.inside or 0)+1 end
        a.spec,a.solid,a.visible=spec,true,spec.visuals_visible~=false
    end
    function b.park(a)
        if b.fail_park==a.id then error('park failed') end
        a.solid,a.visible=false,false
        b.events[#b.events+1]={'park',a.id}
    end
    function b.set_visual_visible(a,visible)
        if b.fail_visibility==a.id then error('visibility failed') end
        a.visible=visible
    end
    function b.valid(a) return b.live[a.id]~=nil end
    function b.destroy(a)
        if b.fail_destroy==a.id then error('destroy failed') end
        b.live[a.id]=nil
        b.events[#b.events+1]={'destroy',a.id}
    end
    function b.move(position)
        if b.fail_move then return false end
        b.position={X=position.X,Y=position.Y,Z=position.Z}
        b.moves=b.moves+1
        return not b.fail_after_move
    end
    function b.count()
        local n=0
        for _ in pairs(b.live) do n=n+1 end
        return n
    end
    return b
end
local function packet(seq,boxes)
    return {session='0123456789abcdef0123456789abcdef',seq=seq or 1,time=1791110000,
        dimension='minecraft:overworld',origin={x=0.5,y=64,z=0.5},
        bounds={-4.5,-2,-4.5,4.5,4,4.5},
        boxes=boxes or {{-4.5,-1,-4.5,4.5,0,4.5}}}
end
local function imported(b)
    for _,a in pairs(b.live) do if a.spec.role=='minecraft' then return a end end
end

check('diagnostic visibility is independent of collision and inherited by replacement actors',function()
    local b=backend(); local t=Terrain.new(b)
    assert(type(t.set_visuals_visible)=='function','Owned diagnostic visibility control is missing')
    assert(t.create(packet())); assert(t.enter()); local moves,count=b.moves,b.count()
    assert(t.set_visuals_visible(false))
    for _,a in pairs(b.live) do assert(a.visible==false) end
    assert(b.moves==moves and b.count()==count and t.pose().state=='entered')
    assert(t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    for _,a in pairs(b.live) do assert(a.visible==false,'New collision actors must inherit hidden diagnostic visuals') end
    assert(t.set_visuals_visible(true))
    for _,a in pairs(b.live) do assert(a.visible==true) end
    assert(t.restore())
end)

check('diagnostic visibility failure remains retryable without deleting physics',function()
    local b=backend(); local t=Terrain.new(b); assert(t.create(packet()))
    b.fail_visibility=1; assert(not t.set_visuals_visible(false)); assert(b.count()==7)
    b.fail_visibility=nil; assert(t.set_visuals_visible(true))
    for _,a in pairs(b.live) do assert(a.visible==true) end
    assert(t.restore())
end)

check('explicit entry maps Minecraft axes and exports EVE feet',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.status()=='ready' and b.moves==0)
    local floor=imported(b).spec
    assert(floor.center.X==10 and floor.center.Y==20 and floor.center.Z==650)
    assert(floor.extent.X==450 and floor.extent.Y==450 and floor.extent.Z==50)
    assert(t.enter()); assert(b.position.Z==795 and b.moves==1)
    b.position={X=210,Y=320,Z=890}
    local p=assert(t.pose())
    assert(p.state=='entered' and p.ack==1)
    assert(p.position.x==2 and p.position.y==1 and p.position.z==3)
    assert(t.restore()); assert(b.position.X==10 and b.position.Y==20 and b.position.Z==100)
    assert(b.count()==0 and t.status()=='idle' and t.pose()==nil)
end)

check('an asymmetric Minecraft box and EVE feet use the same positive horizontal basis',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(1,{{1,0,2,2,1,4}})))
    local spec=imported(b).spec
    assert(spec.center.X==160 and spec.center.Y==320 and spec.center.Z==750)
    assert(spec.extent.X==50 and spec.extent.Y==100 and spec.extent.Z==50)
    b.position={X=160,Y=320,Z=790}
    local pose=t.pose()
    assert(pose.position.x==1.5 and pose.position.y==0 and pose.position.z==3)
    assert(t.restore())
end)

check('entry refuses an imported block intersecting the capsule',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(1,{{-1,0,-1,1,2,1}})))
    assert(not t.enter()); assert(b.moves==0)
    assert(t.restore())
end)

check('the lab floor keeps standing EVE feet inside the Minecraft pose bounds',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(1,{}))); assert(t.enter())
    local floor
    for _,spec in ipairs(t.shapes()) do if spec.role=='lab-floor' then floor=spec end end
    assert(floor)
    local surface=floor.center.Z+floor.extent.Z
    -- Bounds minimum Y=-2 maps to UE Z=500; contact gets a small inward margin.
    assert(surface>=501 and surface<=503)
    assert(floor.center.Z-floor.extent.Z<500)
    b.position.Z=surface+90-0.5 -- Allow a small native contact penetration.
    local pose=assert(t.pose())
    assert(pose.position.y>=-2 and pose.position.y<=4)
    assert(t.restore())
end)

check('failed configuration owns the actor before rollback and supports cleanup retry',function()
    local b=backend(); local t=Terrain.new(b)
    b.fail_configure=1; b.fail_destroy=1
    assert(not t.create(packet())); assert(b.count()==1)
    assert(t.status()=='cleanup-required' and not t.enter())
    assert(not t.update(packet(2)))
    b.fail_destroy=nil; assert(t.restore()); assert(b.count()==0)
end)

check('partial entry and failed return retain support until a successful return',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); local count=b.count()
    b.fail_after_move=true; assert(not t.enter()); assert(b.position.Z==795)
    b.fail_move=true; assert(not t.restore()); assert(b.count()==count)
    b.fail_after_move=false; b.fail_move=false
    assert(t.restore()); assert(b.position.Z==100 and b.count()==0)
end)

check('updates build replacement support before retiring the previous floor',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b)
    b.events={}
    assert(t.update(packet(2,{{-4.5,-1,-4.5,0.5,0,4.5},{0.5,-1,-4.5,4.5,0,4.5}})))
    assert(not old.solid and not old.visible and t.pose().ack==2,'the old floor is parked without collision')
    local old_park,new_spawn
    for i,event in ipairs(b.events) do
        if event[1]=='configure' then new_spawn=i end
        if event[1]=='park' and event[2]==old.id then old_park=i end
    end
    assert(new_spawn and old_park and new_spawn<old_park)
    assert(t.restore()); assert(b.count()==0,'return destroys the parked floor too')
end)

check('updates rebuild only the boxes that changed',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(1,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}}))); assert(t.enter())
    local floor=imported(b); local count=b.count()
    b.events={}
    local ok,message=t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{-3,0,-3,-2,1,-2}}))
    assert(ok and message:find('1 boxes added, 1 removed, 1 kept',1,true),message)
    assert(#b.events==3 and b.events[1][1]=='spawn' and b.events[3][1]=='park','one spawn, then one removal')
    assert(b.live[floor.id] and b.count()==count+1 and t.pose().ack==2,'the unchanged floor keeps its actor')
    assert(t.update(packet(3,{{-4.5,-1,-4.5,4.5,0,4.5},{-3,0,-3,-2,1,-2}})))
    assert(#b.events==3 and t.pose().ack==3,'a revision without geometry changes touches no actor')
    assert(t.restore())
end)

check('a deeper patch raises the lab so its floor stays clear of the start',function()
    local b=backend(); local t=Terrain.new(b)
    local p=packet(1,{{-16.5,-4,-16.5,16.5,-3,16.5}}); p.bounds={-16.5,-4,-16.5,16.5,8,16.5}
    assert(t.create(p))
    local floor
    for _,spec in ipairs(t.shapes()) do if spec.role=='lab-floor' then floor=spec end end
    assert(floor.center.Z+floor.extent.Z>=100+400 and floor.center.Z+floor.extent.Z<=100+403,'lab floor 4 m above the start')
    assert(imported(b).spec.center.Z==900-350,'Minecraft y maps from the raised anchor')
    assert(t.enter()); assert(b.position.Z==900+95)
    assert(t.restore())
end)

check('new body overlap is deferred while floor contact and unchanged geometry are allowed',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(1,{{-3,-1,-3,3,0,3}}))); assert(t.enter())
    b.position.Z=790 -- Capsule feet touch the original Minecraft floor.
    local count=b.count()
    assert(not t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{-1,0,-1,1,1,1}})))
    assert(b.count()==count and t.pose().ack==1)
    assert(t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    assert(t.pose().ack==2)
    b.position={X=260,Y=270,Z=790} -- Existing block contact may overlap the conservative actor geometry.
    assert(t.update(packet(3,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3},{-3,0,-3,-2,1,-2}})))
    assert(t.restore())
end)

check('failed update rolls back new actors while preserving the live patch',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_configure=b.next_id+1
    assert(not t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    assert(b.count()==count and b.live[old.id] and t.status()=='entered' and t.pose().ack==1)
    b.fail_configure=nil
    assert(t.update(packet(2))); assert(t.restore())
end)

check('a failure on the second new box also removes the first',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local count=b.count()
    local boxes={{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3},{-3,0,-3,-2,1,-2}}
    b.fail_configure=b.next_id+2
    assert(not t.update(packet(2,boxes)))
    assert(b.count()==count and t.status()=='entered' and t.pose().ack==1)
    b.fail_configure=nil
    assert(t.update(packet(2,boxes))); assert(b.count()==count+2 and t.pose().ack==2)
    assert(t.restore())
end)

check('failed update cleanup retains every actor and blocks further mutation',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_configure=b.next_id+1; b.fail_destroy=b.next_id+1
    assert(not t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    assert(b.count()==count+1 and b.live[old.id] and t.status()=='cleanup-required')
    assert(not t.enter() and not t.update(packet(3)))
    assert(t.pose().state=='idle')
    b.fail_destroy=nil; assert(t.restore()); assert(b.count()==0 and b.position.Z==100)
end)

check('old actor parking failure retains the new supporting floor until return',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_park=old.id
    assert(not t.update(packet(2,{{-4.5,-1.5,-4.5,4.5,0,4.5}}))); assert(b.count()==count+1)
    assert(t.status()=='cleanup-required' and t.pose().ack==1 and old.solid)
    assert(not t.update(packet(3)),'nothing reuses or moves an actor before the return')
    assert(t.restore()); assert(b.count()==0)
end)

check('context and anchor changes cannot move EVE or mutate existing terrain',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local count=b.count(); local moves=b.moves
    for _,field in ipairs({'session','dimension','origin'}) do
        local p=packet(2)
        if field=='session' then p.session=string.rep('a',32)
        elseif field=='dimension' then p.dimension='minecraft:the_nether'
        else p.origin.x=1.5 end
        assert(not t.update(p)); assert(b.count()==count and b.moves==moves)
    end
    b.world_id=3
    assert(not t.restore() and not t.update(packet(2)) and t.pose()==nil)
    assert(b.count()==count and b.moves==moves)
    b.world_id=2; b.pawn_id=9; assert(not t.restore())
    b.pawn_id=1; assert(t.restore())
end)

check('old revisions and missing guard actors cannot enter or change the terrain',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet(2))); local count=b.count()
    assert(not t.update(packet(1))); assert(b.count()==count)
    assert(t.update(packet(2))); assert(b.count()==count)
    for id,a in pairs(b.live) do if a.spec.role=='lab-floor' then b.live[id]=nil end end
    assert(not t.enter()); assert(b.moves==0)
    assert(t.restore())
end)

-- The window follows EVE: Minecraft moves its bounds within the same anchor.
local function moved(seq,boxes,bounds)
    local p=packet(seq,boxes); p.bounds=bounds; return p
end
local function roles(t,role)
    local found={}
    for _,spec in ipairs(t.shapes()) do if spec.role==role then found[#found+1]=spec end end
    return found
end
check('a window move places the new guards and terrain before parking the old ones',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter())
    local old={}
    for id in pairs(b.live) do old[id]=true end
    b.events={}
    local ok,message=t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},{-2.5,-2,-4.5,6.5,4,4.5}))
    assert(ok and message:find('1 boxes added, 1 removed, 0 kept',1,true) and message:find('window moved',1,true),message)
    local first_park
    for i,event in ipairs(b.events) do
        if event[1]=='park' then first_park=first_park or i else assert(not first_park,'a placement after a removal') end
    end
    assert(b.spawns==14 and first_park==15 and #b.events==21,'six guards and the new floor, then the old floor and the six old guards')
    for id in pairs(old) do assert(not b.live[id].solid and not b.live[id].visible,'every old actor is parked') end
    assert(t.pose().ack==2 and t.status()=='entered')
    local walls=roles(t,'lab-wall')
    assert(#walls==4 and #roles(t,'lab-floor')==1 and #roles(t,'lab-ceiling')==1)
    local xs={}
    for _,wall in ipairs(walls) do xs[#xs+1]=wall.center.X-wall.extent.X end
    table.sort(xs)
    assert(xs[1]==10+(-2.5-0.25)*100 and xs[4]==10+6.5*100,'the side walls stand at the new bounds')
    assert(t.restore()); assert(b.count()==0)
end)

check('after a window moved away from the anchor, F11 then F5 relinks once the window is back at the anchor',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter())
    b.position.X=b.position.X+8*100 -- She walked 8 blocks east.
    local far={4.5,-2,-4.5,12.5,4,4.5}
    local ok,message=t.update(moved(2,{{4.5,-1,-4.5,12.5,0,4.5}},far))
    assert(ok and message:find('window moved',1,true) and t.pose().ack==2,message)
    assert(t.restore()); assert(b.count()==0)
    ok,message=t.create(moved(2,{{4.5,-1,-4.5,12.5,0,4.5}},far))
    assert(not ok and message:find('anchor must fit',1,true),'The far window cannot hold the anchor: '..tostring(message))
    assert(t.create(packet(3)),'Minecraft brought the window back to the anchor')
    assert(t.enter() and t.status()=='entered'); assert(t.restore())
end)

check('a window move is deferred while EVE is outside the new window or a new guard would intersect her',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter())
    local count=b.count(); b.events={}
    local ok,message=t.update(moved(2,{{11.5,-1,-4.5,20.5,0,4.5}},{11.5,-2,-4.5,20.5,4,4.5}))
    assert(not ok and message:find('EVE is outside the new window',1,true),message)
    ok,message=t.update(moved(2,{{-0.2,-1,-4.5,8.8,0,4.5}},{-0.2,-2,-4.5,8.8,4,4.5}))
    assert(not ok and message:find('a new guard intersects EVE',1,true),message)
    assert(#b.events==0 and b.count()==count and t.pose().ack==1 and t.status()=='entered')
    assert(t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},{-2.5,-2,-4.5,6.5,4,4.5})),'the move is retried with a later packet')
    assert(t.pose().ack==2)
    assert(t.restore())
end)

check('a failed guard spawn rolls the window move back',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter())
    local count,floor=b.count(),imported(b)
    b.fail_configure=b.next_id+3
    assert(not t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},{-2.5,-2,-4.5,6.5,4,4.5})))
    assert(b.count()==count and b.live[floor.id] and t.status()=='entered' and t.pose().ack==1)
    assert(#roles(t,'lab-wall')==4,'only the applied window keeps its guards')
    b.fail_configure=nil
    assert(t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},{-2.5,-2,-4.5,6.5,4,4.5})))
    assert(t.pose().ack==2 and b.count()==2*count,'the new window in use, the old one parked')
    assert(t.restore()); assert(b.count()==0)
end)

-- Ten small boxes away from EVE (the floor stays): b.spawn_cost 1.5 ms imports three per 4 ms frame.
local function scattered()
    local boxes={{-4.5,-1,-4.5,4.5,0,4.5}}
    for i=1,10 do boxes[#boxes+1]={-4.5+i*0.5,1,2,-4.25+i*0.5,2,3} end
    return boxes
end
check('a large revision imports a few boxes per frame and keeps the applied revision until it is complete',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter())
    local count=b.count(); b.spawn_cost=0.0015
    local ok,message=t.update(packet(2,scattered()))
    assert(ok and message=='Importing Minecraft collision revision 2: 7 boxes left.',message)
    assert(t.pose().ack==1 and b.count()==count+3)
    ok,message=t.update(packet(2,scattered()))
    assert(ok and message=='Importing Minecraft collision revision 2: 7 boxes left.' and b.count()==count+3,
        'a heartbeat of the importing revision changes nothing')
    ok,message=t.step(); assert(ok and message==nil and b.count()==count+6 and t.pose().ack==1)
    assert(t.step())
    ok,message=t.step()
    assert(ok and message:find('revision 2: 10 boxes added, 0 removed, 1 kept',1,true)
        and message:find('over 4 frames; reused 0, spawned 10, slowest op 1.5 ms',1,true),message)
    assert(t.pose().ack==2 and b.count()==count+10)
    ok,message=t.step(); assert(ok and message==nil,'nothing left to import')
    assert(t.restore()); assert(b.count()==0)
end)

check('a newer revision during an import keeps what is already spawned',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter()); b.spawn_cost=0.0015
    assert(t.update(packet(2,scattered())))
    local spawned={}
    for id,a in pairs(b.live) do if a.spec.role=='minecraft' then spawned[id]=a end end
    local boxes=scattered(); table.remove(boxes,3); boxes[#boxes+1]={2,1,2,3,2,3}
    b.events={}
    assert(t.update(packet(3,boxes)))
    for id in pairs(spawned) do assert(b.live[id],'spawned boxes stay until the import ends') end
    local done
    for _=1,5 do
        local ok,message=t.step(); assert(ok)
        if message then done=message; break end
    end
    assert(done and done:find('revision 3: 8 boxes added, 1 removed, 3 kept',1,true),done)
    local parked=0
    for _,event in ipairs(b.events) do if event[1]=='park' then parked=parked+1 end end
    assert(parked==1 and t.pose().ack==3 and b.count()==6+#boxes+1,'only the dropped box goes, once')
    assert(t.restore())
end)

check('a queued box EVE walked into waits until she leaves',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter()); b.spawn_cost=0.0015
    local boxes=scattered(); boxes[#boxes+1]={2,0,-1,3,1,0}
    assert(t.update(packet(2,boxes)))
    local count=b.count()
    b.position={X=260,Y=-30,Z=795} -- Minecraft (2.5, 0.05, -0.5): inside the last queued box.
    local ok,message
    for _=1,3 do ok,message=t.step() end
    assert(ok and message:find('waiting',1,true) and b.count()==count+7 and t.pose().ack==1,message)
    ok,message=t.step(); assert(ok and message:find('waiting',1,true) and b.count()==count+7)
    b.position={X=10,Y=20,Z=795}
    ok,message=t.step()
    assert(ok and message:find('revision 2: 11 boxes added',1,true) and t.pose().ack==2,message)
    assert(t.restore())
end)

check('a window moved back during its import drops the new guards at once',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter()); b.spawn_cost=0.0015
    local boxes=scattered(); boxes[1]={-2.5,-1,-4.5,6.5,0,4.5}
    assert(t.update(moved(2,boxes,{-2.5,-2,-4.5,6.5,4,4.5})))
    assert(#roles(t,'lab-wall')==8 and t.pose().ack==1,'both windows are guarded while the move imports')
    local ok,done=t.update(packet(3,scattered()))
    assert(ok and #roles(t,'lab-wall')==4,'the applied window keeps only its own guards')
    -- The dropped guards are parked and placed again as boxes at no spawn cost, so the rest fits this frame.
    assert(done:find('revision 3: 8 boxes added',1,true) and done:find('reused 6, spawned 2',1,true)
        and not done:find('window moved',1,true) and t.pose().ack==3,done)
    assert(t.restore()); assert(b.count()==0)
end)

-- The pool: the first window move spawns the second guard set; later moves place parked actors instead.
local EAST={-2.5,-2,-4.5,6.5,4,4.5}
local function solid(b)
    local ids={}
    for id,a in pairs(b.live) do if a.solid then ids[id]=true end end
    return ids
end
-- Exactly the terrain's shapes have a solid actor; parked actors have none.
local function consistent(b,t)
    local shapes,n={},0
    for _,spec in ipairs(t.shapes()) do shapes[spec]=true end
    for _,a in pairs(b.live) do if a.solid then n=n+1; assert(shapes[a.spec],'a solid actor outside the terrain') end end
    assert(n==#t.shapes(),'a terrain shape without its actor')
end
check('after one window move, moves alternate the two guard sets and reuse retired boxes without spawning',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter())
    local home=solid(b)
    local ok,message=t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},EAST))
    assert(ok and message:find('reused 0, spawned 7',1,true),message)
    local east=solid(b); consistent(b,t)
    b.spawn_cost=0.0015; b.events={} -- Spawning would take frames; placing parked actors does not.
    ok,message=t.update(packet(3))
    assert(ok and message:find('1 boxes added, 1 removed, 0 kept',1,true) and message:find('over 1 frames; reused 7, spawned 0',1,true)
        and message:find('window moved',1,true),message)
    local kinds={}
    for i,event in ipairs(b.events) do kinds[i]=event[1] end
    assert(table.concat(kinds,' ')==string.rep('configure ',7)..string.rep('park ',6)..'park',
        'the guards, then the box, from the pool; then the old window parked')
    local now=solid(b); consistent(b,t)
    for id in pairs(home) do assert(now[id],'the first guard set and floor hold EVE again') end
    for id in pairs(east) do assert(not now[id] and not b.live[id].visible,'the second set is parked and hidden') end
    assert(t.set_visuals_visible(true))
    for id in pairs(east) do assert(not b.live[id].visible,'parked visuals stay hidden') end
    ok,message=t.update(moved(4,{{-2.5,-1,-4.5,6.5,0,4.5}},EAST))
    assert(ok and message:find('reused 7, spawned 0',1,true),message)
    now=solid(b); consistent(b,t)
    for id in pairs(east) do assert(now[id],'the second set is back') end
    assert(b.spawns==14 and b.count()==14 and b.inside==nil)
    b.events={}
    assert(t.restore()); assert(b.count()==0 and #b.events==14,'return destroys both guard sets, the terrain and the pool once each')
end)

check('a parked actor is placed only where a new one could be, never around EVE',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter())
    assert(t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},EAST)))
    b.events={}
    local ok,message=t.update(moved(3,{{-2.5,-1,-4.5,6.5,0,4.5},{-1,0,-1,1,1,1}},EAST))
    assert(not ok and message:find('new geometry intersects EVE',1,true),message)
    b.position.X=b.position.X-1000 -- She stepped out of both windows.
    ok,message=t.update(packet(3))
    assert(not ok and message:find('EVE is outside the new window',1,true),message)
    assert(#b.events==0 and b.inside==nil and t.pose().ack==2,'a deferred revision touches no parked actor')
    consistent(b,t); assert(t.restore()); assert(b.count()==0)
end)

check('a parked actor that fails its setup rolls the move back like a new one',function()
    local b=backend(); local t=Terrain.new(b,100,b.time)
    assert(t.create(packet())); assert(t.enter())
    assert(t.update(moved(2,{{-2.5,-1,-4.5,6.5,0,4.5}},EAST)))
    local applied=solid(b)
    b.fail_configure=1 -- The first parked actor placed again: the first guard set's floor.
    local ok,message=t.update(packet(3))
    assert(not ok and message:find('previous terrain retained',1,true),message)
    assert(not b.live[1] and t.status()=='entered' and t.pose().ack==2,'the failed actor is destroyed, the applied window kept')
    local now=solid(b); consistent(b,t)
    for id in pairs(applied) do assert(now[id]) end
    b.fail_configure=nil
    ok,message=t.update(packet(3))
    assert(ok and message:find('reused 6, spawned 1',1,true) and t.pose().ack==3,message)
    consistent(b,t); assert(t.restore()); assert(b.count()==0)
end)
print(string.format('%d terrain lifecycle tests passed; native physics requires in-game verification.',tests))
