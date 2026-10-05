package.path = 'stellar/Mods/StellarCollisionProbe/Scripts/?.lua;' .. package.path
local Probe = require('probe')
local tests = 0
local function check(name, run)
    run()
    tests = tests + 1
    print('PASS ' .. name)
end
local function backend()
    local b = {live = {}, next_id = 0, pawn_id = 1, world_id = 2,
        position = {X = 10, Y = 20, Z = 100}, moves = 0}
    function b.snapshot()
        return {pawn_id=b.pawn_id, world_id=b.world_id,
            position={X=b.position.X, Y=b.position.Y, Z=b.position.Z},
            rotation={Pitch=0,Yaw=0,Roll=0}, half_height=90, radius=35}
    end
    function b.spawn(spec)
        b.next_id = b.next_id + 1
        if b.fail_at == b.next_id then error('spawn failed') end
        local a = {id=b.next_id, spec=spec}
        b.live[a.id] = a
        return a
    end
    function b.configure(a)
        if b.fail_configure == a.id then error("configuration failed") end
    end
    function b.destroy(a)
        if b.fail_destroy == a.id then error('destroy failed') end
        b.live[a.id] = nil
    end
    function b.valid(a) return b.live[a.id] ~= nil end
    function b.move(position, rotation)
        if b.fail_move then return false end
        b.position = {X=position.X, Y=position.Y, Z=position.Z}
        b.moves = b.moves + 1
        return not b.fail_after_move
    end
    function b.count()
        local n = 0
        for _ in pairs(b.live) do n = n + 1 end
        return n
    end
    return b
end
check('creation does not teleport; explicit entry lands above only the added floor', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); assert(b.moves==0)
    local floor
    for _, a in pairs(b.live) do if a.spec.role=='floor' then floor=a.spec end end
    assert(floor and floor.center.Z-floor.extent.Z > 190)
    assert(p.enter()); assert(b.moves==1)
    assert(b.position.Z > floor.center.Z+floor.extent.Z+90)
    assert(p.restore()); assert(b.position.X==10 and b.position.Z==100)
    assert(b.count()==0)
end)
check('partial spawn failure rolls back prior actors without moving EVE', function()
    local b=backend(); b.fail_at=3; local p=Probe.new(b)
    assert(not p.create()); assert(b.count()==0 and b.moves==0)
    b.fail_at=nil; assert(p.create()); assert(p.restore())
end)
check('failed return retains the supporting floor and can be retried', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); assert(p.enter()); local n=b.count()
    b.fail_move=true; assert(not p.restore()); assert(b.count()==n)
    b.fail_move=false; assert(p.restore()); assert(b.count()==0)
end)
check('world changes never apply the old return position or delete old-world actors', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); assert(p.enter()); local n=b.count(); local moves=b.moves
    b.world_id=9; assert(not p.restore()); assert(b.moves==moves and b.count()==n)
end)
check('obstacle toggle leaves the floor and recreates only the obstacle', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); local floor_id; local n=b.count()
    for id,a in pairs(b.live) do if a.spec.role=='floor' then floor_id=id end end
    assert(p.toggle()); assert(b.count()==n-1 and b.live[floor_id])
    assert(p.toggle()); assert(b.count()==n and b.live[floor_id])
    assert(p.restore())
end)
check('an obstacle cannot be recreated through the player', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); local obstacle
    for _,a in pairs(b.live) do if a.spec.role=='obstacle' then obstacle=a.spec end end
    assert(p.toggle()); local n=b.count()
    b.position=obstacle.center; assert(not p.toggle()); assert(b.count()==n)
end)
check('cleanup failure retains ownership for retry', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); b.fail_destroy=2
    assert(not p.restore()); assert(b.count()==1)
    b.fail_destroy=nil; assert(p.restore()); assert(b.count()==0)
end)
check('incomplete rollback is cleanup-only and cannot teleport or add obstacles', function()
    local b=backend(); b.fail_at=3; b.fail_destroy=2; local p=Probe.new(b)
    assert(not p.create()); assert(b.count()==1)
    assert(not p.enter()); assert(b.moves==0)
    assert(not p.toggle()); assert(b.count()==1)
    assert(p.status()=='cleanup-required')
    b.fail_destroy=nil; assert(p.restore()); assert(b.count()==0)
end)
check('a relocated player is returned even when entry verification fails', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create()); b.fail_after_move=true
    assert(not p.enter()); assert(b.position.Z>100)
    b.fail_after_move=false; assert(p.restore())
    assert(b.position.X==10 and b.position.Z==100 and b.count()==0)
end)
check('failed configuration keeps ownership when actor destruction also fails', function()
    local b=backend(); b.fail_configure=1; b.fail_destroy=1; local p=Probe.new(b)
    assert(not p.create()); assert(b.count()==1)
    assert(not p.enter()); assert(p.status()=='cleanup-required')
    b.fail_destroy=nil; assert(p.restore()); assert(b.count()==0)
end)
check('missing support prevents entry', function()
    local b=backend(); local p=Probe.new(b)
    assert(p.create())
    for id,a in pairs(b.live) do if a.spec.role=='floor' then b.live[id]=nil end end
    assert(not p.enter()); assert(b.moves==0)
end)
print(string.format('%d lifecycle tests passed; Unreal physics remains untested.',tests))
