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
engine.effects=function(_,attached_only) return attached_only and active or {} end -- On the body; EVE's own have none.
RUN=string.rep('8',32); request(); logging.tick(); logging.tick(); logging.tick()
assert(#logged==1,'Every holder\'s walk counts: the body keeps the effect while EVE\'s own walk sees none: '..table.concat(logged,'\n'))
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
engine.names=function(c) if c.drone then local bones={} for i=1,513 do bones[i]='B'..i end return bones,{} end return names(c) end
engine.identities=function() return {eve,companion} end
local _,wide=droned.tick()
assert(wide:find('1 components',1,true) and wide:find('skipped Drone.CharacterMesh0 (not streamable)',1,true),wide)
-- Suits carry up to 364 bones: EVE's own components stream up to 512; past that the stream stops rather than drop her.
local function boned(count)
    return function(c) if c.drone then return names(c) end local bones={} for i=1,count do bones[i]='B'..i end return bones,{} end
end
engine.names=boned(512)
engine.identities=function() return {eve} end
local _,suit=droned.tick()
local published=(read('eve-pose-components.0') or '')..(read('eve-pose-components.1') or '')
assert(suit:find('1 components',1,true) and published:find(' 512 0 CharacterMesh0 CH_P_EVE_52_TypeB -\n',1,true),suit)
engine.names=boned(513)
engine.identities=function() return {{name=eve.name,object=eve.object,class=eve.class,mesh=eve.mesh,mesh_name='CH_P_EVE_Nikke_02'}} end
assert(not pcall(droned.tick),'A component past 512 bones stops the stream')
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
engine.cloth=function(c) return {tick=3,blend=1.0,disabled=false,teleport_distance=0.0,teleport_rotation=0.5,reset=true,scale=1,wait=false,
    uro=false,rendered=true,suspended=false} end -- bPauseClothTickAlways unreadable.
local tail={name='SBPonytail',object=0x7f0000021000,class=0x7f0000002000,mesh=0x7f0000023000,mesh_name='CH_P_EVE_Hair_PonyTail_2',leader='CharacterMesh0'}
local drone_tail={name='SBPonytail',object=0x7f0000031000,class=0x7f0000002000,mesh=0x7f0000033000,mesh_name='M',drone=true}
engine.identities=function() return {eve,tail,drone_tail} end
local worn=Stream.new(engine,directory,function() return now end,function(line) cloth_log[#cloth_log+1]=line end,function() return place end)
RUN=string.rep('4',32); request(); worn.tick()
assert(read('eve-pose-components.1'):find('\nEFFECTS none\nCLOTH 2\nEND ',1,true),read('eve-pose-components.1'))
assert(worn.components()[2].cloth and not worn.components()[1].cloth and not worn.components()[3].cloth,'Only EVE\'s ponytail')
assert(#cloth_log==1 and cloth_log[1]=='Cloth: SBPonytail tick option 3, blend weight 1, simulation disabled false; teleport distance 0,'
    ..' rotation 0.5, reset after teleport true, max distance scale 1, wait for parallel task false, update rate optimizations false,'
    ..' recently rendered true, suspended false, tick paused always ?',cloth_log[1])
place={session=string.rep('a',32),anchor={X=0,Y=0,Z=0},scale=100}; worn.tick()
assert(#cloth_log==1,'Logged once per run')
-- The cloth's 10 + 3 * 17309 floats share the slot: with the ponytail listed, the drone gives way at 13567 table floats.
engine.names=function(c) if c.drone or c.name:find('^Part') then local bones={} for i=1,250 do bones[i]='B'..i end return bones,{} end return names(c) end
local tailed={eve}
for i=1,5 do tailed[#tailed+1]=heavy[i+1] end
tailed[#tailed+1]=companion
engine.identities=function() return tailed end
local _,untailed=worn.tick()
assert(untailed:find('7 components',1,true) and not untailed:find('skipped',1,true),untailed)
table.insert(tailed,2,tail)
local _,crowded_tail=worn.tick()
assert(crowded_tail:find('7 components',1,true) and crowded_tail:find('skipped Drone.CharacterMesh0 (no room)',1,true),crowded_tail)
engine.names=names
engine.cloth=function() error('no reflection') end
RUN=string.rep('3',32); request(); worn.tick()
assert(cloth_log[2]:find('^Cloth: SBPonytail state unavailable: ') and cloth_log[2]:find('no reflection',1,true),cloth_log[2])
engine.identities=function() return {eve} end
RUN=string.rep('2',32); request(); worn.tick()
assert(read('eve-pose-components.1'):find('\nCLOTH none\nEND ',1,true) and #cloth_log==2,'No ponytail, no cloth')
engine.cloth=nil
-- The lookups run all at once for a new run, then take turns: per check one component's parameters, one known effect
-- path or the controller, and one effect walk (a component's attached effects or EVE's own) while walks are costly, so
-- no check holds Stellar Blade's game thread for long.
local calls
local function count(kind) local n=0 for _,c in ipairs(calls) do if c.kind==kind then n=n+1 end end return n end
local glow_late=false
local body={name='Body',object=0x7f0000001000,class=0x7f0000002000,mesh=0x7f0000003000,mesh_name='M'}
local blade={name='Sword',object=0x7f0000041000,class=0x7f0000002000,mesh=0x7f0000043000,mesh_name='W'}
engine.identities=function() return {body,blade,companion} end
engine.controller=function() calls[#calls+1]={kind='controller'} end
engine.parameters=function(list)
    calls[#calls+1]={kind='parameters',list=list}
    if glow_late and list[1]==blade then
        return {{component=1,slot='Blade_Edge',name='Opacity',object=0x7f0000009000,class=0x7f000000a000,offset=0x168,element=2,index=4711,floats=1}}
    end
    return {}
end
-- Three known effect paths: all at once for a new run, then one per turn (each lookup is a StaticFindObject of about 4 ms).
engine.known_effects=function(index) calls[#calls+1]={kind='known',index=index}; return {},3 end
engine.effects=function(list,attached_only) calls[#calls+1]={kind='effects',list=list,attached_only=attached_only}; return {},{} end
local timing_log,seconds={},0
local paced=Stream.new(engine,directory,function() return now end,function(line) timing_log[#timing_log+1]=line end,
    function() return place end,function() seconds=seconds+0.001; return seconds end)
calls={}; RUN=string.rep('1',32); request(); paced.tick()
assert(count('parameters')==3 and count('effects')==4 and count('known')==1 and count('controller')==1,'A new run looks everything up')
local function known_index() for _,c in ipairs(calls) do if c.kind=='known' then return c.index or 'all' end end end
assert(known_index()=='all','A new run looks every known effect path up')
local turns,pawn_walked,paths={},false,{}
for _=1,20 do
    calls={}; paced.tick()
    assert(count('parameters')+count('known')+count('controller')==1 and count('effects')==1,'One lookup and one effect walk per check')
    local index=known_index()
    if index then assert(math.type(index)=='integer','One known effect path per turn'); paths[#paths+1]=index end
    for _,c in ipairs(calls) do
        if c.kind=='parameters' then assert(#c.list==1); turns[c.list[1].object]=true end
        if c.kind=='effects' then
            if #c.list==0 then pawn_walked=true else assert(#c.list==1 and c.attached_only==true,'A component alone, attached effects only') end
        end
    end
end
assert(turns[body.object] and turns[blade.object] and turns[companion.object] and pawn_walked,'Every holder gets its turn')
assert(table.concat(paths,' ')=='1 2 3 1','The known effect paths take turns, then start over: '..table.concat(paths,' '))
-- A dynamic material instance created later, as the sword's first glow, is published within one round of turns.
glow_late=true
local glowing=false
for _=1,5 do local _,m=paced.tick(); glowing=glowing or (m~=nil and m:find('1 material parameters',1,true)~=nil) end
assert(glowing,'A late glow parameter is published')
local function published_tables() return (read('eve-pose-components.0') or '')..(read('eve-pose-components.1') or '') end
assert(published_tables():find('\nPARAMETER 1 2 Blade_Edge Opacity 00007f0000009000 ',1,true),'Looked up alone, the blade stays component 2')
-- Every 50 checks, each step's mean and longest time.
for _=1,50 do paced.tick() end
local timing_line
for _,line in ipairs(timing_log) do if line:find('^Pose stream timing') then timing_line=line end end
assert(timing_line and timing_line:find('; identities 1.0/1.0 x50;',1,true) and timing_line:find('^Pose stream timing %(ms mean/max, runs%): tick %d')
    and timing_line:find('; effects 1.0/1.0 x5%d;') and timing_line:find('; material keys -;',1,true),timing_line)
-- With material keys, a component's parameters are walked again only when its key changes, one component a check;
-- effect walks that fit the budget all run every check, as before the turns.
local material={[body.object]='a',[blade.object]='b',[companion.object]='c'}
engine.material_key=function(c) return material[c.object] end
glow_late=false
local keyed=Stream.new(engine,directory,function() return now end,function() end,function() return place end,function() return 0 end)
calls={}; RUN=string.rep('0',32); request(); keyed.tick()
assert(count('parameters')==3,'A new run looks every component up')
for _=1,6 do
    calls={}; keyed.tick()
    assert(count('parameters')==0 and count('effects')==4,'Unchanged keys walk no parameters; cheap effect walks all run')
end
glow_late=true; material[body.object],material[blade.object]='a2','b2'
local function looked_up() for _,c in ipairs(calls) do if c.kind=='parameters' then return c.list[1] end end end
calls={}; keyed.tick()
assert(count('parameters')==1 and looked_up()==body,'One changed component a check, the body first')
calls={}; local _,keyed_glow=keyed.tick()
assert(count('parameters')==1 and keyed_glow and keyed_glow:find('1 material parameters',1,true),tostring(keyed_glow))
assert(published_tables():find('\nPARAMETER 1 2 Blade_Edge Opacity 00007f0000009000 ',1,true),'The blade is component 2')
calls={}; keyed.tick()
assert(count('parameters')==0,'Both keys are up to date')
-- A failed lookup is retried on its turn even while its key stays the same.
local failing=true
engine.parameters=function(list) calls[#calls+1]={kind='parameters',list=list}; if failing then error('reflection hiccup') end; return {} end
material[body.object]='a3'; keyed.tick(); failing=false
local retried=false
for _=1,6 do calls={}; keyed.tick(); retried=retried or count('parameters')==1 end
assert(retried,'A failed lookup comes back on its turn')
-- A replaced drone is a new composition, but only the new component's parameters are walked.
local other={name='Drone.CharacterMesh0',object=0x7f0000051000,class=0x7f0000012000,mesh=0x7f0000013000,mesh_name='CH_NPC_Drone',drone=true}
material[other.object]='d'
engine.identities=function() return {body,blade,other} end
calls={}; keyed.tick()
assert(count('parameters')==1 and looked_up()==other and count('effects')==4,'Only the new drone is looked up; the walks all rerun')
assert(known_index()~='all','A new composition does not look every known effect path up again')
-- A table that fails to publish keeps its lookups: the next checks do not walk everything again.
local names_ok=engine.names
engine.names=function() error('a bone name the stream cannot carry') end
calls={}; RUN=string.rep('b',32); request()
assert(not pcall(keyed.tick) and count('parameters')==3,'A new run looks every component up')
calls={}
assert(not pcall(keyed.tick) and count('parameters')==0,'A failed publication does not repeat the lookups')
engine.names=names_ok
engine.material_key=nil
for name in io.popen('ls '..directory):lines() do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
print('PASS pose stream: request lease, exact component table, unchanged compositions, placement changes, skipped names, reload and run numbering, controller rotation, pawn and look scales, control binding, material parameters, effect log, effect layout, the drone and the ponytail\'s cloth')
