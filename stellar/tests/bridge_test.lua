package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Bridge=require('bridge')
local path=os.tmpname(); os.remove(path); assert(os.execute('mkdir '..string.format('%q',path)))
local now=1791110000
local session='0123456789abcdef0123456789abcdef'
local state,ack,return_fails,deferred='idle',1,false,false
local lifecycle={}
function lifecycle.create(packet) state='ready'; ack=packet.seq; return true,'created' end
function lifecycle.enter() state='entered'; return true,'entered' end
function lifecycle.update(packet)
    if deferred then return false,'new geometry intersects EVE' end
    ack=packet.seq; return true,'updated'
end
function lifecycle.restore()
    if return_fails then return false,'floor retained' end
    state='idle'; return true,'returned'
end
function lifecycle.status() return state end
function lifecycle.pose()
    if state=='idle' then return nil end
    return {session=session,state=state,ack=ack,position={x=1,y=2,z=-3}}
end
local function write(text)
    local f=assert(io.open(path..'/terrain.txt','wb')); assert(f:write(text)); assert(f:close())
end
local function terrain(seq,time)
    return string.format('SBMC 2 TERRAIN\nSESSION %s\nSEQ %d\nTIME %d\nWORLD minecraft:overworld 0.5 64 0.5\nBOUNDS -4.5 -2 -4.5 4.5 4 4.5\nCOUNT 1\nBOX -4.5 -1 -4.5 4.5 0 4.5\nEND %d\n',session,seq,time,seq)
end
local function read(name)
    local f=io.open(path..'/'..name,'rb'); if not f then return nil end
    local text=f:read('*a'); f:close(); return text
end
local bridge=Bridge.new(lifecycle,path,function()end,function()return now end)
assert(type(bridge.binding)=='function','Fresh visual binding is missing')
assert(bridge.binding()==nil)
assert(bridge.pose()==nil,'inactive bridge cannot attach a rendering frame to a session')
write(terrain(1,now-6))
assert(not bridge.create() and state=='idle','stale terrain must not create a scene')
write(terrain(1,now))
assert(bridge.create()); assert(bridge.enter())
assert(bridge.pose().session==session and bridge.pose().state=='entered')
local binding=assert(bridge.binding())
assert(binding.session==session and binding.dimension=='minecraft:overworld' and binding.ack==1)
binding.origin.x=99; binding.bounds[1]=99; binding.position.x=99
assert(bridge.binding().origin.x==0.5 and bridge.binding().bounds[1]==-4.5 and bridge.binding().position.x==1,
    'Binding snapshots must not expose mutable bridge state')
bridge.tick(bridge.read())
assert(read('eve.0') and read('eve.1'),'both publication slots should exist')
local previous=read('eve.1')
write(terrain(2,now)); bridge.tick(bridge.read()); assert(ack==2,'new geometry is forwarded')
assert(read('eve.1')==previous,'one valid slot must survive each publication')
deferred=true
for i=1,7 do
    now=now+1; write(terrain(3,now)); bridge.tick(bridge.read())
end
assert(state=='entered' and ack==2,'healthy deferred edits must retain the scene and previous terrain ack')
assert(bridge.binding().ack==2,'Rendering binding must use the actually imported collision revision')
deferred=false
now=now+6; return_fails=true
bridge.tick(bridge.read()); assert(state=='entered','failed return retains supporting scene')
assert(bridge.pose()==nil,'a stopping bridge must not authorize new linked rendering frames')
assert(bridge.binding()==nil,'Stopping or expired terrain cannot authorize native visuals')
local slots=(read('eve.0') or '')..(read('eve.1') or '')
assert(slots:find('STATE idle 2',1,true),'expired bridge must stop Minecraft pose authority even if return fails')
return_fails=false; bridge.tick(bridge.read()); assert(state=='idle')
local Protocol=require('bridge_protocol')
local old_sequence=math.max(Protocol.parse_eve(read('eve.0')).seq,Protocol.parse_eve(read('eve.1')).seq)
-- A recreated bridge simulates a game restart with the same Minecraft session.
bridge=Bridge.new(lifecycle,path,function()end,function()return now end)
write(terrain(1,now)); assert(bridge.create())
local new_sequence=math.max(Protocol.parse_eve(read('eve.0')).seq,Protocol.parse_eve(read('eve.1')).seq)
assert(new_sequence>old_sequence,'same-session imports must advance past both existing slot sequences')
write(terrain(2,now):gsub('END 2','END 3'))
local packet=bridge.read(); assert(packet==nil,'partial/mismatched packet rejected')
now=now+6; bridge.tick(packet); assert(state=='idle','invalid snapshots cannot keep a session alive')
assert(os.execute('rm -r '..string.format('%q',path)))
print('PASS bridge freshness, revision transfer, two-slot publishing and guarded disconnect')
