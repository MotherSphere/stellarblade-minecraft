package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Lock=require('lock_on')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local f=assert(io.open('protocol/fixtures/lock.txt','rb')); local fixture=f:read('a'); f:close()

-- The shared fixture parses; none releases; a malformed file does not parse.
local parsed=Lock.parse(fixture)
assert(parsed and parsed.session==string.rep('a',32) and parsed.seq==3 and parsed.target.x==2.5 and parsed.target.y==1 and parsed.target.z==-3.25)
assert(Lock.parse(fixture:gsub('TARGET [^\n]+','TARGET none')).target==nil)
for _,bad in ipairs({fixture:gsub('END 3','END 4'),fixture:gsub('2.500','5000'),fixture:gsub('2.500','nan'),fixture:gsub('2.500 ',''),fixture:sub(1,-2)}) do
    assert(Lock.parse(bad)==nil,bad)
end

local SESSION=string.rep('b',32)
local wall,real=1791300000,100.0
local logged,calls={},{}
local state={locked=false,spawned=0,released=0,camera=true,spawn_error=nil,release_error=nil,engage=true,swept=0}
local engine={}
function engine.lock_sweep() state.swept=state.swept+1; return 0 end
function engine.lock_spawn(at)
    if state.spawn_error then error(state.spawn_error) end
    state.spawned=state.spawned+1; state.at=at
    return {id=state.spawned},'proxy '..state.spawned
end
function engine.lock_camera(value) local previous=state.camera; state.camera=value; return previous end
function engine.lock_follow(proxy,at) state.at=at; return state.locked end
function engine.lock_engage(proxy,at) state.locked=state.engage; return state.locked end
function engine.lock_release(proxy,camera)
    if state.release_error then error(state.release_error) end
    state.released=state.released+1; state.locked=false; state.camera=camera
    return 'released proxy '..proxy.id
end
function engine.lock_status() return 'status' end
function engine.lock_draw() return nil end -- Drawn already, until the sword section.
function engine.lock_sheathe() return 'sheathing' end
local place={session=SESSION,anchor={X=1000,Y=2000,Z=300},scale=100}
local lock=Lock.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
local seq=0
local function publish(target,session,time)
    seq=seq+1
    write('eve-lock.txt',string.format('SBMC_LOCK 1\nSESSION %s\nSEQ %d\nTIME %d\nTARGET %s\nEND %d\n',session or SESSION,seq,time or wall*1000,target,seq))
end
local function last() return logged[#logged] end

lock.tick()
assert(state.swept==1 and state.spawned==0,'Leftovers are swept once; no file, no lock')
-- A target spawns the proxy at its Unreal position (Minecraft X, Y, Z to Unreal X, Z, Y around the anchor), turns
-- Stellar Blade's lock camera off and engages.
publish('2.000 1.000 -3.000'); lock.tick()
assert(state.spawned==1 and state.at.X==1200 and state.at.Y==1700 and state.at.Z==400,'Unreal position from the anchor')
assert(state.camera==false and state.locked and last()=='Lock: engaged: status',last())
lock.tick(); lock.tick()
assert(state.spawned==1,'One proxy while held')
-- Heartbeats keep it; a dropped lock is re-engaged after the retry delay and counted.
real=real+0.3; publish('2.500 1.000 -3.000'); state.locked=false; lock.tick()
assert(state.locked and table.concat(logged,'\n'):find('lost 1: status',1,true),table.concat(logged,'\n'))
real=real+0.1; publish('2.500 1.000 -3.000'); state.locked=false; lock.tick()
assert(not state.locked,'No new engage within the retry delay')
real=real+0.2; publish('2.500 1.000 -3.000'); lock.tick()
assert(state.locked,'Engaged again once the delay is over')
-- Without heartbeats the lease runs out and everything is undone, the camera flag restored.
real=real+0.7; lock.tick()
assert(state.released==1 and state.camera==true and last():find('released (stale)',1,true),last())
-- A heartbeat that stopped earlier does not come back as a fresh lock.
lock.tick()
assert(state.spawned==1,'A stale publication does not lock again')
-- Minecraft's release undoes at once.
publish('1.000 1.000 1.000'); lock.tick()
assert(state.spawned==2)
publish('none'); lock.tick()
assert(state.released==2 and last():find('released (Minecraft released)',1,true),last())
-- A foreign session is ignored.
publish('1.000 1.000 1.000',string.rep('c',32)); lock.tick()
assert(state.spawned==2,'Another session is ignored')
-- Link loss undoes everything; a failed release is retried first every frame and blocks new locks until it works.
publish('1.000 1.000 1.000'); lock.tick()
assert(state.spawned==3)
state.release_error='EVE busy'
place=nil; lock.tick(); lock.tick()
assert(state.released==2 and table.concat(logged,'\n'):find('release failed, retrying',1,true))
state.release_error=nil; place={session=SESSION,anchor={X=1000,Y=2000,Z=300},scale=100}; lock.tick()
assert(state.released==3 and table.concat(logged,'\n'):find('released (retry)',1,true))
-- F11's release with nothing held is a no-op success.
assert(lock.release('F11 return requested'))
-- Three failed spawns stop the tries for this Lua lifetime.
state.spawn_error='class missing'
for _=1,5 do publish('1.000 1.000 1.000'); real=real+0.1; lock.tick() end
local failures=0
for _,line in ipairs(logged) do if line:find('proxy failed',1,true) then failures=failures+1 end end
assert(failures==3,'Spawning stops after three failures')
-- A new Lua ignores an old first publication; engage errors count toward the same cap, so a broken proxy is not
-- respawned every frame.
state.spawn_error=nil
local fresh=Lock.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
local spawned=state.spawned
publish('1.000 1.000 1.000',nil,(wall-3)*1000); fresh.tick()
assert(state.spawned==spawned,'An old first publication is ignored')
engine.lock_engage=function() error('engage broke') end
for _=1,10 do publish('1.000 1.000 1.000'); real=real+0.05; fresh.tick() end
assert(state.spawned==spawned+3 and last():find('released (error: ',1,true),'Three tries, then no more spawns: '..last())
-- The sword: drawn once the lock holds, checked every second; a draw that did not stick is retried at once, five in a
-- row at most; a drawn check resets the count; release sheathes once, even when the release itself is retried.
engine.lock_engage=function(proxy,at) state.locked=true; return true end
local sword,draws='Sword',{}
function engine.lock_draw(instant)
    if sword~='Sword' then return nil end
    draws[#draws+1]=instant; return instant and 'instant draw' or 'drawing'
end
local sheathes=0
function engine.lock_sheathe() sheathes=sheathes+1; return 'sheathing' end
local armed=Lock.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
publish('1.000 1.000 1.000',nil,wall*1000); armed.tick()
assert(#draws==0,'No draw before the lock holds')
publish('1.000 1.000 1.000'); armed.tick()
assert(#draws==1 and draws[1]==false and last()=='Lock: sword 1/5: drawing',last())
real=real+0.5; publish('1.000 1.000 1.000'); armed.tick()
assert(#draws==1,'One check per second')
real=real+0.5; publish('1.000 1.000 1.000'); armed.tick()
assert(#draws==2 and draws[2]==true,'A draw that did not stick is retried with the instant attach')
sword='Draw'; real=real+0.5; publish('1.000 1.000 1.000'); armed.tick()
real=real+0.5; publish('1.000 1.000 1.000'); armed.tick()
sword='Sword'; real=real+1; publish('1.000 1.000 1.000'); armed.tick()
assert(#draws==3 and draws[3]==false,'Seen drawn: the next draw is a plain one again')
for _=1,10 do real=real+0.5; publish('1.000 1.000 1.000'); armed.tick() end
assert(#draws==7,'Five tries in a row at most: '..#draws)
state.release_error='EVE busy'; publish('none'); armed.tick(); armed.tick()
state.release_error=nil; armed.tick()
assert(sheathes==1 and table.concat(logged,'\n'):find('Lock: sword: sheathing',1,true),'One sheathe across release retries')
os.remove(directory..'/eve-lock.txt'); os.remove(directory)
print('Lock-on: parse, Unreal placement, engage and relock, lease, release paths, retry and spawn cap, sword draw and sheathe')
