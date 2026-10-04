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
        position={X=10,Y=20,Z=100},moves=0,events={}}
    function b.snapshot()
        return {pawn_id=b.pawn_id,world_id=b.world_id,
            position={X=b.position.X,Y=b.position.Y,Z=b.position.Z},
            rotation={Pitch=0,Yaw=0,Roll=0},half_height=90,radius=35}
    end
    function b.spawn(spec)
        b.next_id=b.next_id+1
        if b.fail_spawn==b.next_id then error('spawn failed') end
        local a={id=b.next_id,spec=spec}
        b.live[a.id]=a
        b.events[#b.events+1]={'spawn',a.id}
        return a
    end
    function b.configure(a)
        if b.fail_configure==a.id then error('configure failed') end
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

check('explicit entry maps Minecraft axes and exports EVE feet',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.status()=='ready' and b.moves==0)
    local floor=imported(b).spec
    assert(floor.center.X==10 and floor.center.Y==20 and floor.center.Z==650)
    assert(floor.extent.X==450 and floor.extent.Y==450 and floor.extent.Z==50)
    assert(t.enter()); assert(b.position.Z==795 and b.moves==1)
    b.position={X=210,Y=-280,Z=890}
    local p=assert(t.pose())
    assert(p.state=='entered' and p.ack==1)
    assert(p.position.x==2 and p.position.y==1 and p.position.z==3)
    assert(t.restore()); assert(b.position.X==10 and b.position.Y==20 and b.position.Z==100)
    assert(b.count()==0 and t.status()=='idle' and t.pose()==nil)
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

check('updates build replacement support before deleting the previous floor',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b)
    b.events={}
    assert(t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    assert(not b.live[old.id] and t.pose().ack==2)
    local old_destroy,new_spawn
    for i,event in ipairs(b.events) do
        if event[1]=='spawn' then new_spawn=i end
        if event[1]=='destroy' and event[2]==old.id then old_destroy=i end
    end
    assert(new_spawn and old_destroy and new_spawn<old_destroy)
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
    b.position={X=260,Y=-230,Z=790} -- Existing block contact may overlap the conservative actor geometry.
    assert(t.update(packet(3,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3},{-3,0,-3,-2,1,-2}})))
    assert(t.restore())
end)

check('failed update rolls back new actors while preserving the live patch',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_configure=b.next_id+2
    assert(not t.update(packet(2,{{-4.5,-1,-4.5,4.5,0,4.5},{2,0,2,3,1,3}})))
    assert(b.count()==count and b.live[old.id] and t.status()=='entered' and t.pose().ack==1)
    b.fail_configure=nil
    assert(t.update(packet(2))); assert(t.restore())
end)

check('failed update cleanup retains every actor and blocks further mutation',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_configure=b.next_id+1; b.fail_destroy=b.next_id+1
    assert(not t.update(packet(2)))
    assert(b.count()==count+1 and b.live[old.id] and t.status()=='cleanup-required')
    assert(not t.enter() and not t.update(packet(3)))
    assert(t.pose().state=='idle')
    b.fail_destroy=nil; assert(t.restore()); assert(b.count()==0 and b.position.Z==100)
end)

check('old actor deletion failure retains the new supporting floor until return',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local old=imported(b); local count=b.count()
    b.fail_destroy=old.id
    assert(not t.update(packet(2))); assert(b.count()==count+1)
    assert(t.status()=='cleanup-required' and t.pose().ack==1)
    b.fail_destroy=nil; assert(t.restore()); assert(b.count()==0)
end)

check('context and anchor changes cannot move EVE or mutate existing terrain',function()
    local b=backend(); local t=Terrain.new(b)
    assert(t.create(packet())); assert(t.enter()); local count=b.count(); local moves=b.moves
    for _,field in ipairs({'session','dimension','origin','bounds'}) do
        local p=packet(2)
        if field=='session' then p.session=string.rep('a',32)
        elseif field=='dimension' then p.dimension='minecraft:the_nether'
        elseif field=='origin' then p.origin.x=1.5
        else p.bounds[1]=-3.5 end
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
print(string.format('%d terrain lifecycle tests passed; native physics requires in-game verification.',tests))
