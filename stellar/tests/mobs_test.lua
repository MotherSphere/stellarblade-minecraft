package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Mobs=require('mobs')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local f=assert(io.open('protocol/fixtures/mobs.txt','rb')); local fixture=f:read('a'); f:close()

-- The shared fixture parses; an empty list parses; malformed, oversized or out-of-range lists do not.
local parsed=Mobs.parse(fixture)
assert(parsed and parsed.session==string.rep('a',32) and parsed.seq==5 and #parsed.boxes==2)
assert(parsed.boxes[1][1]==1.2 and parsed.boxes[1][5]==1.95 and parsed.boxes[2][6]==3.4)
assert(#Mobs.parse((fixture:gsub('COUNT 2\n.-END','COUNT 0\nEND'))).boxes==0)
local nine=fixture:gsub('COUNT 2\n(MOB [^\n]+\n)','COUNT 9\n'..string.rep('%1',9))
for _,bad in ipairs({fixture:gsub('END 5','END 6'),fixture:gsub('COUNT 2','COUNT 3'),fixture:gsub('1.200','40'),
        fixture:gsub('1.200','nan'),fixture:gsub('1.200','1.900'),fixture:gsub('MOB 1.200 ','MOB '),fixture:sub(1,-2),
        fixture:gsub('SESSION a','SESSION A'),nine}) do
    assert(Mobs.parse(bad)==nil,bad)
end

local SESSION=string.rep('b',32)
local wall,real=1791300000,100.0
local logged={}
local state={spawned=0,destroyed=0,placed=0,eve={X=1000,Y=2000,Z=395},spawn_error=nil,destroy_error=nil}
local engine={}
function engine.blocker_spawn()
    if state.spawn_error then error(state.spawn_error) end
    state.spawned=state.spawned+1
    return {id=state.spawned}
end
function engine.blocker_configure(actor)
    if state.configure_error then error(state.configure_error) end
    return 'blocks channel 24'
end
function engine.blocker_place(actor,spec,solid)
    state.placed=state.placed+1
    actor.spec,actor.solid=spec,solid
end
function engine.blocker_destroy(actor)
    if state.destroy_error then error(state.destroy_error) end
    state.destroyed=state.destroyed+1; actor.gone=true
end
function engine.snapshot() return {position=state.eve,radius=40,half_height=95,ignoring=state.ignoring} end
local place={session=SESSION,anchor={X=1000,Y=2000,Z=300},scale=100}
local function new() return Mobs.new(engine,directory,function() return wall end,function() return real end,
    function(line) logged[#logged+1]=line end,function() return place end) end
local mobs=new()
local seq=0
local held={} -- The actors the engine was given, by spawn order.
local spawn=engine.blocker_spawn
engine.blocker_spawn=function() local actor=spawn(); held[#held+1]=actor; return actor end
local function publish(boxes,session,time)
    seq=seq+1
    local lines={}
    for _,b in ipairs(boxes) do lines[#lines+1]=string.format('MOB %.3f %.3f %.3f %.3f %.3f %.3f\n',table.unpack(b)) end
    write('eve-mobs.txt',string.format('SBMC_MOBS 1\nSESSION %s\nSEQ %d\nTIME %d\nCOUNT %d\n%sEND %d\n',
        session or SESSION,seq,time or wall*1000,#boxes,table.concat(lines),seq))
end
local function last() return logged[#logged] end
local zombie={1.2,0,-0.3,1.8,1.95,0.3} -- 1.5 blocks east of the origin.

mobs.tick()
assert(state.spawned==0,'No file, no walls')
-- A listed mob gets a wall at its Unreal box (Minecraft X, Y, Z to Unreal X, Z, Y around the anchor), solid away from EVE.
publish({zombie}); mobs.tick()
local a=held[1]
assert(state.spawned==1 and a.solid and a.spec.center.X==1150 and a.spec.center.Y==2000 and math.abs(a.spec.center.Z-397.5)<1e-9,
    'Unreal box from the anchor')
assert(math.abs(a.spec.extent.X-30)<1e-9 and math.abs(a.spec.extent.Z-97.5)<1e-9 and last()=='Mobs: first wall spawned, blocks channel 24',last())
-- Unchanged frames touch nothing; a moved mob moves its wall.
local placed=state.placed
mobs.tick(); mobs.tick()
assert(state.placed==placed,'No engine call while nothing changes')
publish({{1.3,0,-0.3,1.9,1.95,0.3}}); mobs.tick()
assert(state.placed==placed+1 and a.spec.center.X==1160)
-- A wall that would land inside EVE (capsule radius 40 at X 1000) waits without collision until she leaves.
publish({{0.2,0,-0.3,0.8,1.95,0.3}}); mobs.tick()
assert(a.solid==false and a.spec.center.X==1050,'Inside EVE: no collision')
state.eve={X=900,Y=2000,Z=395}; mobs.tick()
assert(a.solid==true,'Solid once she has left it')
-- A second mob gets a second wall; a shorter list parks the extra one without collision, kept for reuse.
publish({zombie,{-3.5,0,2,-2.1,0.9,3.4}}); mobs.tick()
assert(state.spawned==2 and held[2].solid and held[2].spec.center.Y==2270)
publish({zombie}); mobs.tick()
assert(held[2].solid==false and state.destroyed==0,'Parked, not destroyed')
publish({zombie,zombie}); mobs.tick()
assert(state.spawned==2 and held[2].solid,'The parked wall comes back')
-- Without heartbeats the lease runs out and every wall is destroyed.
real=real+0.7; mobs.tick()
assert(state.destroyed==2 and last()=='Mobs: released 2 (stale)',last())
-- A stale publication does not come back; a newer one does.
mobs.tick()
assert(state.spawned==2,'A stale publication does not spawn again')
publish({zombie}); mobs.tick()
assert(state.spawned==3)
-- A foreign session is ignored; link loss releases; a failed destroy is retried first every frame.
publish({zombie,zombie},string.rep('c',32)); mobs.tick()
assert(state.spawned==3,'Another session is ignored')
state.destroy_error='busy'
place=nil; mobs.tick(); mobs.tick()
assert(state.destroyed==2 and table.concat(logged,'\n'):find('release failed, retrying: ',1,true))
local failures=0
for _,line in ipairs(logged) do if line:find('release failed',1,true) then failures=failures+1 end end
assert(failures==1,'One line for a release that keeps failing')
state.destroy_error=nil; place={session=SESSION,anchor={X=1000,Y=2000,Z=300},scale=100}; mobs.tick()
assert(state.destroyed==3 and table.concat(logged,'\n'):find('Mobs: released 1 (retry)',1,true),'Released once it works')
-- F11 with nothing held is a no-op success.
assert(mobs.release('F11 return requested'))
-- Spawn and placement errors stop new walls after three, and release what exists.
state.spawn_error='class missing'
for _=1,5 do publish({zombie}); real=real+0.05; mobs.tick() end
local errors=0
for _,line in ipairs(logged) do if line:find('Mobs: error',1,true) then errors=errors+1 end end
assert(errors==3,'Spawning stops after three errors')
-- A new Lua ignores an old first publication.
state.spawn_error=nil
local fresh=new()
local spawned=state.spawned
publish({zombie},nil,(wall-3)*1000); fresh.tick()
assert(state.spawned==spawned,'An old first publication is ignored')
publish({zombie}); fresh.tick()
assert(state.spawned==spawned+1)
-- While Stellar Blade lets EVE pass through characters (her evades), no wall is solid; they come back after, logged once
-- each way.
local passing=held[#held]
state.ignoring=true; publish({zombie}); fresh.tick()
assert(passing.solid==false and last()=='Mobs: EVE passes through characters: walls open',last())
state.ignoring=false; publish({zombie}); fresh.tick()
assert(passing.solid==true and last()=='Mobs: EVE meets characters: walls solid',last())
-- A wall whose setup fails is already owned: the error releases (destroys) it.
local setup=new()
state.configure_error='no box'
local destroyed=state.destroyed
publish({zombie}); setup.tick()
assert(state.destroyed==destroyed+1 and last():find('released 1 (error)',1,true),last())
state.configure_error=nil
print('PASS mob walls: shared grammar, Unreal placement, EVE overlap and pass-through, parking, lease, session, release retry, setup ownership, error cap')
