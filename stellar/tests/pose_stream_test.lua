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
assert(#stream.components()==2 and stream.components()[2].object==0x7f0000001800,'The streamed meshes, for the pose guard')
-- Entering the link changes the placement: a new generation in the other slot.
place={session=string.rep('b',32),anchor={X=1.5,Y=-2,Z=3},scale=100}
assert(select(2,stream.tick()):find('generation 2',1,true))
assert(read('eve-pose-components.0'):find('SESSION '..string.rep('b',32)..'\nANCHOR 1.5 -2 3 100\n',1,true))
-- A swapped mesh is a new composition; a name the wire cannot carry is skipped.
components[2]={name='Mesh Face',object=0x7f0000001800,class=0x7f0000002000,mesh=0x7f0000004000,mesh_name='X',leader='CharacterMesh0'}
local _,changed=stream.tick()
assert(changed:find('generation 3: 1 components',1,true) and changed:find('skipped Mesh Face',1,true),changed)
assert(#stream.components()==1,'The pose guard follows the new composition')
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
-- EVE's combat-named members are logged once per Lua lifetime, after the effects.
engine.combat_names=function() return {'function SBCharacter.ApplyDamage','property SBCharacter.bJustParry'} end
logged={}
local combat=Stream.new(engine,directory,function() return now end,function(line) logged[#logged+1]=line end,function() return place end)
RUN=string.rep('6',32); request(); combat.tick(); combat.tick()
assert(#logged==3 and logged[1]=='EVE combat: function SBCharacter.ApplyDamage' and logged[3]=='EVE combat: 2 names',table.concat(logged,'\n'))
engine.combat_names=nil
-- Effects: the reading layout and the known assets, then assets seen active, follow the parameters.
engine.effect_layout=function() return {children=0x130,asset=0x6a8,template=0x4b0,active_byte=0x88,active_mask=0x10,
    niagara=0x7f000000b000,particle=0x7f000000c000} end
engine.known_effects=function() return {{name='NS_A_Weapon_Signal_Sword',object=0x7f000000d000}} end
engine.effects=function() return {'flame'},{{name='NS_A_WeaponFX_SwordFlame_01',object=0x7f000000e000},{name='bad name',object=0x7f000000f000}} end
local fx=Stream.new(engine,directory,function() return now end,function() end,function() return place end)
RUN=string.rep('7',32); request(); fx.tick()
assert(read('eve-pose-components.1'):find('\nPARAMETERS 0\nEFFECTS 00000130 000006a8 000004b0 00000088 10 00007f000000b000 00007f000000c000 2\n'
    ..'EFFECT 1 00007f000000d000 NS_A_Weapon_Signal_Sword\nEFFECT 2 00007f000000e000 NS_A_WeaponFX_SwordFlame_01\nCLOTH none\nEND ',1,true),
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
assert(read('eve-pose-components.1'):find('\nPARAMETERS 1\nPARAMETER 1 1 Blade_Edge Opacity 00007f0000009000 00007f000000a000 00000168 2 4711 1\nEFFECTS none\nCLOTH none\nEND ',1,true),
    'A name the wire cannot carry is dropped')
engine.parameters=function() error('GetComparisonIndex unavailable') end
RUN=string.rep('9',32); request()
local _,without_glow=reloaded.tick()
assert(without_glow:find('material parameters unavailable: ',1,true) and read('eve-pose-components.1'):find('\nPARAMETERS 0\nEFFECTS none\nCLOTH none\nEND ',1,true),without_glow)
-- EVE's drone streams after her components and gives way to them: past sixteen components or one slot of floats.
engine.parameters=function() return {} end
local drone_log={}
local droned=Stream.new(engine,directory,function() return now end,function(line) drone_log[#drone_log+1]=line end,function() return place end)
local eve={name='CharacterMesh0',object=0x7f0000001000,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='CH_P_EVE_52_TypeB'}
local companion={name='Drone.CharacterMesh0',object=0x7f0000011000,class=0x7f0000012000,mesh=0x7f0000013000,mesh_name='CH_NPC_Drone',drone=true}
local state='CH_Drone_BP_C_1 actor shown, mesh visible'
engine.identities=function() return {eve,companion},state end
RUN=string.rep('5',32); request()
local _,with_drone=droned.tick()
assert(with_drone:find('2 components',1,true) and read('eve-pose-components.1'):find(
    '\nCOMPONENT 2 00007f0000011000 00007f0000012000 00007f0000013000 3 0 Drone.CharacterMesh0 CH_NPC_Drone -\n',1,true),with_drone)
droned.tick(); state='CH_Drone_BP_C_1 actor hidden, mesh visible'; droned.tick()
assert(#drone_log==2 and drone_log[1]=='EVE drone: CH_Drone_BP_C_1 actor shown, mesh visible'
    and drone_log[2]=='EVE drone: CH_Drone_BP_C_1 actor hidden, mesh visible','The drone state is logged when it changes')
local crowd={}
for i=1,16 do crowd[i]={name='C'..i,object=0x7f0000100000+0x1000*i,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='M'} end
crowd[17]=companion
engine.identities=function() return crowd end
local _,crowded=droned.tick()
assert(crowded:find('16 components',1,true) and crowded:find('skipped Drone.CharacterMesh0 (no room)',1,true),crowded)
local names=engine.names
engine.names=function(c) if c.drone then local bones={} for i=1,300 do bones[i]='B'..i end return bones,{} end return names(c) end
engine.identities=function() return {eve,companion} end
local _,wide=droned.tick()
assert(wide:find('1 components',1,true) and wide:find('skipped Drone.CharacterMesh0 (not streamable)',1,true),wide)
local heavy={eve}
for i=1,6 do heavy[#heavy+1]={name='Part'..i,object=0x7f0000100000+0x1000*i,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='M'} end
heavy[#heavy+1]=companion
engine.names=function(c) if c.drone or c.name:find('^Part') then local bones={} for i=1,250 do bones[i]='B'..i end return bones,{} end return names(c) end
engine.identities=function() return heavy end
local _,full=droned.tick()
assert(full:find('7 components',1,true) and full:find('skipped Drone.CharacterMesh0 (no room)',1,true),full)
engine.names=names
-- EVE's ponytail: CLOTH names its index, the stream marks it for world_render.lua and logs its cloth state once per run.
local cloth_log={}
engine.cloth=function(c) return {tick=3,blend=1,disabled=false} end
local tail={name='SBPonytail',object=0x7f0000021000,class=0x7f0000002000,mesh=0x7f0000023000,mesh_name='CH_P_EVE_Hair_PonyTail_2',leader='CharacterMesh0'}
local drone_tail={name='SBPonytail',object=0x7f0000031000,class=0x7f0000002000,mesh=0x7f0000033000,mesh_name='M',drone=true}
engine.identities=function() return {eve,tail,drone_tail} end
local worn=Stream.new(engine,directory,function() return now end,function(line) cloth_log[#cloth_log+1]=line end,function() return place end)
RUN=string.rep('4',32); request(); worn.tick()
assert(read('eve-pose-components.1'):find('\nEFFECTS none\nCLOTH 2\nEND ',1,true),read('eve-pose-components.1'))
assert(worn.components()[2].cloth and not worn.components()[1].cloth and not worn.components()[3].cloth,'Only EVE\'s ponytail')
assert(#cloth_log==1 and cloth_log[1]=='Cloth: SBPonytail tick option 3, blend weight 1, simulation disabled false',cloth_log[1])
place={session=string.rep('a',32),anchor={X=0,Y=0,Z=0},scale=100}; worn.tick()
assert(#cloth_log==1,'Logged once per run')
engine.cloth=function() error('no reflection') end
RUN=string.rep('3',32); request(); worn.tick()
assert(cloth_log[2]:find('^Cloth: SBPonytail state unavailable: ') and cloth_log[2]:find('no reflection',1,true),cloth_log[2])
engine.identities=function() return {eve} end
RUN=string.rep('2',32); request(); worn.tick()
assert(read('eve-pose-components.1'):find('\nCLOTH none\nEND ',1,true) and #cloth_log==2,'No ponytail, no cloth')
engine.cloth=nil
for name in io.popen('ls '..directory):lines() do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
print('PASS pose stream: request lease, exact component table, unchanged compositions, placement changes, skipped names, reload and run numbering, controller rotation, pawn and look scales, control binding, material parameters, effect log, effect layout, the drone and the ponytail\'s cloth')
