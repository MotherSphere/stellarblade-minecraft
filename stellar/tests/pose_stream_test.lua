package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Stream=require('pose_stream')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function read(name) local f=io.open(directory..'/'..name,'rb'); if not f then return nil end local v=f:read('a');f:close();return v end
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local RUN=string.rep('a',32)
local now=1791300000
local function request(state,time) write('eve-pose-request.txt','SBMC_POSE_REQUEST 1\nRUN '..RUN..'\nTIME '..(time or now*1000)..'\nSTATE '..(state or 'active')..'\nEND\n') end
local components,names_calls={},0
local engine={}
function engine.identities() return components end
function engine.parameters() return {} end
function engine.names(c)
    names_calls=names_calls+1
    if c.leader then return {},{'jawOpen','blink'} end
    return {'Root','Bip001','Bip001-Head'},{}
end
local place
local stream=Stream.new(engine,directory,function() return now end,print,function() return place end)
components={{name='CharacterMesh0',object=0x7f0000001000,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='CH_P_EVE_52_TypeB'},
    {name='Mesh_Face',object=0x7f0000001800,class=0x7f0000002000,mesh=0x7f0000003800,mesh_name='CH_P_EVE_Face_Jali3',leader='CharacterMesh0'}}

assert(Stream.parse_request('SBMC_POSE_REQUEST 1\nRUN '..RUN..'\nTIME 5\nSTATE active\nEND\n').active)
assert(not Stream.parse_request('SBMC_POSE_REQUEST 1\nRUN abc\nTIME 5\nSTATE active\nEND\n'))
assert(stream.tick() and not read('eve-pose-components.1'),'No request, no publication')
request(nil,(now-10)*1000)
assert(stream.tick() and not read('eve-pose-components.1'),'A stale request publishes nothing')
request()
local ok,message=stream.tick()
assert(ok and message:find('generation 1: 2 components, no entered link',1,true),message)
local fixture=assert(io.open('protocol/fixtures/pose-components.txt','rb')); local expected=fixture:read('a'); fixture:close()
assert(read('eve-pose-components.1')==expected,'The published table matches the shared fixture byte for byte')
local ok2,quiet=stream.tick()
assert(ok2 and quiet==nil and names_calls==2,'An unchanged composition is not described again')
-- Entering the link changes the placement: a new generation in the other slot.
place={session=string.rep('b',32),anchor={X=1.5,Y=-2,Z=3},scale=100}
assert(select(2,stream.tick()):find('generation 2',1,true))
assert(read('eve-pose-components.0'):find('SESSION '..string.rep('b',32)..'\nANCHOR 1.5 -2 3 100\n',1,true))
-- A swapped mesh is a new composition; a name the wire cannot carry is skipped.
components[2]={name='Mesh Face',object=0x7f0000001800,class=0x7f0000002000,mesh=0x7f0000004000,mesh_name='X',leader='CharacterMesh0'}
local _,changed=stream.tick()
assert(changed:find('generation 3: 1 components',1,true) and changed:find('skipped Mesh Face',1,true),changed)
-- A Lua reload continues after the published generation of the same run.
local reloaded=Stream.new(engine,directory,function() return now end,print,function() return place end)
assert(select(2,reloaded.tick()):find('generation 4',1,true))
-- A different run restarts its own numbering.
RUN=string.rep('c',32); request()
assert(select(2,reloaded.tick()):find('generation 1',1,true))
-- Stopping goes idle once.
request('stopped')
assert(select(2,reloaded.tick())=='Pose stream idle.' and select(2,reloaded.tick())==nil)
-- Unaligned addresses or oversized compositions fail loudly instead of publishing.
request()
components={{name='Body',object=0x7f0000001001,class=0x10,mesh=0x20,mesh_name='M'}}
assert(not pcall(reloaded.tick))
-- The controller's rotation offset, pawn and look scales are published, and the avatar control binding exists only
-- for an entered link.
components={{name='Body',object=0x7f0000001000,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='M'}}
place=nil
engine.controller=function() return {object=0x7f0000005000,class=0x7f0000006000,offset=0x318,
    pawn=0x7f0000007000,pawn_class=0x7f0000008000,yaw_scale=2.5,pitch_scale=-2.5} end
RUN=string.rep('f',32); request()
assert(reloaded.tick() and reloaded.binding()==nil,'No link, no control binding')
assert(read('eve-pose-components.1'):find('\nCONTROLLER 00007f0000005000 00007f0000006000 00000318 00007f0000007000 00007f0000008000 2.5 -2.5\nCOUNT 1\n',1,true))
place={session=string.rep('b',32),anchor={X=0,Y=0,Z=0},scale=100}
reloaded.tick()
local binding=reloaded.binding()
assert(binding and binding.run==RUN and binding.session==string.rep('b',32),'An entered link binds controls to this run')
request('stopped'); reloaded.tick()
assert(reloaded.binding()==nil,'A stopped request revokes the binding')
engine.controller=function() error('reflection unavailable') end
request(); local _,without=reloaded.tick()
assert(without:find('no controller rotation',1,true) and read('eve-pose-components.1'):find('\nCONTROLLER none\n',1,true),without)
-- An unusable look scale publishes no controller rather than one the adapter would reject.
engine.controller=function() return {object=0x7f0000005000,class=0x7f0000006000,offset=0x318,
    pawn=0x7f0000007000,pawn_class=0x7f0000008000,yaw_scale=0,pitch_scale=-2.5} end
RUN=string.rep('e',32); request(); reloaded.tick()
assert(read('eve-pose-components.1'):find('\nCONTROLLER none\n',1,true))
-- Active effects are logged when they change, and a failing lookup only once.
local logged={}
local logging=Stream.new(engine,directory,function() return now end,function(line) logged[#logged+1]=line end,function() return place end)
local active={'NS_A_WeaponFX_SwordFlame_01@SBSkeletalMeshComponent_1:FX_WeaponFX_Begin'}
engine.effects=function() return active end
RUN=string.rep('8',32); request(); logging.tick(); logging.tick()
active={}; logging.tick()
engine.effects=function() error('Niagara unavailable') end
logging.tick(); logging.tick()
assert(#logged==3 and logged[1]=='EVE effects: NS_A_WeaponFX_SwordFlame_01@SBSkeletalMeshComponent_1:FX_WeaponFX_Begin'
    and logged[2]=='EVE effects: none' and logged[3]:find('unavailable: ',1,true),table.concat(logged,'\n'))
engine.effects=nil
-- Effects: the reading layout and the known assets, then assets seen active, follow the parameters.
engine.effect_layout=function() return {children=0x130,asset=0x6a8,template=0x4b0,active_byte=0x88,active_mask=0x10,
    niagara=0x7f000000b000,particle=0x7f000000c000} end
engine.known_effects=function() return {{name='NS_A_Weapon_Signal_Sword',object=0x7f000000d000}} end
engine.effects=function() return {'flame'},{{name='NS_A_WeaponFX_SwordFlame_01',object=0x7f000000e000},{name='bad name',object=0x7f000000f000}} end
local fx=Stream.new(engine,directory,function() return now end,function() end,function() return place end)
RUN=string.rep('7',32); request(); fx.tick()
assert(read('eve-pose-components.1'):find('\nPARAMETERS 0\nEFFECTS 00000130 000006a8 000004b0 00000088 10 00007f000000b000 00007f000000c000 2\n'
    ..'EFFECT 1 00007f000000d000 NS_A_Weapon_Signal_Sword\nEFFECT 2 00007f000000e000 NS_A_WeaponFX_SwordFlame_01\nEND ',1,true),
    read('eve-pose-components.1'))
engine.effect_layout,engine.known_effects,engine.effects=nil,nil,nil
-- Glow parameters of dynamic material instances follow the components; a failing lookup publishes none.
engine.controller=function() return nil end
engine.parameters=function(list)
    assert(#list==1,'Parameters are looked up on the published components')
    return {{component=1,slot='Blade_Edge',name='Opacity',object=0x7f0000009000,class=0x7f000000a000,offset=0x168,element=2,index=4711,floats=1},
        {component=1,slot='Blade_Edge',name='Emissive Color',object=0x7f0000009000,class=0x7f000000a000,offset=0x178,element=0,index=4712,floats=4}}
end
RUN=string.rep('d',32); request()
local _,glow=reloaded.tick()
assert(glow:find('1 material parameters',1,true),glow)
assert(read('eve-pose-components.1'):find('\nPARAMETERS 1\nPARAMETER 1 1 Blade_Edge Opacity 00007f0000009000 00007f000000a000 00000168 2 4711 1\nEFFECTS none\nEND ',1,true),
    'A name the wire cannot carry is dropped')
engine.parameters=function() error('GetComparisonIndex unavailable') end
RUN=string.rep('9',32); request()
local _,without_glow=reloaded.tick()
assert(without_glow:find('material parameters unavailable: ',1,true) and read('eve-pose-components.1'):find('\nPARAMETERS 0\nEFFECTS none\nEND ',1,true),without_glow)
for name in io.popen('ls '..directory):lines() do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
print('PASS pose stream: request lease, exact component table, unchanged compositions, placement changes, skipped names, reload and run numbering, controller rotation, pawn and look scales, control binding, material parameters, effect log and effect layout')
