-- UE4SS value shapes seen in game: converted lists of RemoteUnrealParam, TArray userdata and nested structs.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local function fname(text) return {type=function() return 'FName' end,ToString=function() return text end} end
local function wrap(value) return {type=function() return 'RemoteUnrealParam' end,get=function() return value end} end
local function tarray(items,wrapped)
    return {type=function() return 'TArray' end,GetArrayNum=function() return #items end,ForEach=function(_,callback)
        for index,value in ipairs(items) do callback(index,wrap(wrapped and wrap(value) or value)) end
    end}
end
local function object(name,fields)
    local o=fields or {}
    o.type=function() return 'UObject' end; o.IsValid=function() return true end
    o.GetFName=function() return fname(name) end; o.GetAddress=function() return name end
    return o
end
local function xf(z) return {Rotation={X=0,Y=0,Z=0,W=1},Translation={X=1,Y=2,Z=z},Scale3D={X=1,Y=1,Z=1}} end
local bodyMesh=object('CH_P_EVE_01_Body',{MorphTargets=tarray({})})
local faceMesh=object('CH_P_EVE_Face_003',{MorphTargets=tarray({object('eyeBlinkLeft'),object('jawOpen')})})
local body=object('Body',{SkeletalMesh=bodyMesh,MasterPoseComponent={Get=function() return nil end}})
function body:GetNumBones() return 2 end
function body:GetAttachParent() return nil end
function body:GetAttachSocketName() return fname('None') end
function body:SnapshotPose(out)
    out.bIsValid=true
    out.BoneNames={fname('Root'),fname('Bip001-Head')}
    out.LocalTransforms=tarray({xf(3),xf(4)},true)
    out.MorphTargetWeights={}
end
function body:K2_GetComponentToWorld() return xf(100) end
local face=object('Face',{SkeletalMesh=faceMesh,MasterPoseComponent=wrap({Get=function() return nil end})})
function face:GetNumBones() return 1 end
function face:GetAttachParent() return body end
function face:GetAttachSocketName() return fname('Bip001-Head') end
function face:SnapshotPose() error('out-param struct not supported') end
function face:GetBoneName() return fname('Jaw') end
function face:GetSocketTransform(name,space) assert(name:ToString()=='Jaw' and space==3); return wrap(xf(7)) end
function face:GetMorphTarget(name) return name:ToString()=='jawOpen' and 0.5 or 0 end
function face:K2_GetComponentToWorld() return xf(200) end
local suit=object('Suit',{SkeletalMesh=object('Suit'),MasterPoseComponent={Get=function() return body end}})
function suit:GetNumBones() return 2 end
function suit:GetAttachParent() return body end
function suit:GetAttachSocketName() return fname('None') end
local broken=object('Broken',{SkeletalMesh=object('X')})
function broken:GetNumBones() error('getter failed') end
local pawn=object('Pawn')
function pawn:K2_GetComponentsByClass(class)
    assert(class.name=='SkeletalMeshComponent')
    return {wrap(body),wrap(face),wrap(suit),wrap(broken)} -- The shape that failed in game.
end
package.loaded.UEHelpers={GetPlayer=function() return pawn end}
StaticFindObject=function(path) assert(path=='/Script/Engine.SkeletalMeshComponent'); local c=object('Class'); c.name='SkeletalMeshComponent'; return c end
local engine=require('pose_adapter').new()
local list=engine.components()
assert(#list==4 and list[1].name=='Body' and list[1].mesh=='CH_P_EVE_01_Body' and list[1].bones==2 and not list[1].leader)
assert(list[2].parent=='Body' and list[2].socket=='Bip001-Head' and #list[2].morphs==2 and list[2].morphs[2]=='jawOpen')
assert(list[3].leader=='Body','Leader-driven components are identified through the weak pointer')
assert(list[4].leader=='unknown' and list[4].note:find('getter failed',1,true),'A failing component is listed without stopping the probe')
local pose=engine.snapshot(list[1].handle)
assert(pose.method=='snapshot' and pose.names[2]=='Bip001-Head' and pose.transforms[2].z==4 and pose.transforms[2].qw==1 and #pose.weights==0)
local fallback=engine.snapshot(list[2].handle)
assert(fallback.method=='sockets' and fallback.fallback:find('out%-param') and fallback.names[1]=='Jaw' and fallback.transforms[1].z==7)
assert(fallback.weights[1]==0 and fallback.weights[2]==0.5)
assert(engine.snapshot(list[2].handle).method=='sockets','A failed snapshot is not retried every frame')
assert(engine.world(list[1].handle).z==100)
-- The ponytail's cloth state for the log: each value read on its own, nil where reflection has none or fails.
local tail=setmetatable({VisibilityBasedAnimTickOption=3,ClothBlendWeight=1,bDisableClothSimulation=false,TeleportDistanceThreshold=0,
    bRecentlyRendered=false,bPauseClothTickAlways=true,on=true,IsClothingSimulationSuspended=function(self) return self.on end,
    GetClothMaxDistanceScale=function() error('no such function') end},
    {__index=function(_,name) if name=='bWaitForParallelClothTask' then error('unreadable') end end})
local cloth=engine.cloth({handle=tail})
assert(cloth.tick==3 and cloth.blend==1 and cloth.disabled==false and cloth.teleport_distance==0 and cloth.rendered==false
    and cloth.paused==true and cloth.suspended==true and cloth.scale==nil and cloth.wait==nil and cloth.teleport_rotation==nil)
-- Glow parameters: only named scalars and vectors of dynamic instances, with their array offset and element.
local function named(text,index) local n=fname(text); n.GetComparisonIndex=function() return index end; return n end
local midClass=object('MaterialInstanceDynamic')
local mid=object('MID',{ScalarParameterValues=tarray({{ParameterInfo={Name=named('Roughness',7)},ParameterValue=0.5},
    {ParameterInfo=wrap({Name=named('Opacity',9)}),ParameterValue=0}},true),VectorParameterValues=tarray({{ParameterInfo={Name=named('Emissive_Color',11)}}})})
mid.GetAddress=function() return 0x7f0000010000 end
mid.GetClass=function() return midClass end
midClass.GetAddress=function() return 0x7f0000020000 end
local offsets={ScalarParameterValues=0x168,VectorParameterValues=0x178}
mid.Reflection=function() return {GetProperty=function(_,name) return {ContainerPtrToValuePtr=function() return 0x7f0000010000+offsets[name] end} end} end
local plain=object('MI'); plain.GetClass=function() return object('MaterialInstanceConstant') end
local sword=object('Sword')
function sword:GetMaterialSlotNames() return tarray({fname('Body'),fname('Blade_Edge')}) end
function sword:GetNumMaterials() return 2 end
function sword:GetMaterial(i) return i==1 and mid or plain end
local glow=engine.parameters({{handle=sword}})
assert(#glow==2 and glow[1].slot=='Blade_Edge' and glow[1].name=='Opacity' and glow[1].element==1 and glow[1].index==9
    and glow[1].offset==0x168 and glow[1].floats==1 and glow[1].class==0x7f0000020000,'The scalar names its element')
assert(glow[2].name=='Emissive_Color' and glow[2].floats==4 and glow[2].offset==0x178 and glow[2].element==0)
-- The material key: each slot's material and a dynamic instance's array lengths, so a notify's first entry changes it
-- without a walk of the arrays.
local material_key=engine.material_key({handle=sword})
assert(material_key=='MI '..tostring(0x7f0000010000)..' 2 1',material_key)
local scalars=mid.ScalarParameterValues
mid.ScalarParameterValues=tarray({{},{},{}})
assert(engine.material_key({handle=sword})=='MI '..tostring(0x7f0000010000)..' 3 1','An added entry changes the key')
mid.ScalarParameterValues=scalars
-- Effects: an active pooled Niagara component attached under the sword is found; an inactive one is not.
local niagaraClass=object('NiagaraComponent')
local function effect(name,asset,active,socket)
    local e=object(name,{Asset=object(asset),AttachChildren=tarray({})})
    e.GetClass=function() return niagaraClass end
    e.IsActive=function() return active end
    e.GetAttachParent=function() return sword end
    e.GetAttachSocketName=function() return fname(socket) end
    return e
end
sword.AttachChildren=tarray({effect('NC1','NS_A_Weapon_Signal_Sword',true,'FX_WeaponFX_Begin'),effect('NC2','NS_Old',false,'X')})
sword.GetClass=function() return object('SkeletalMeshComponent') end
package.loaded.UEHelpers.GetPlayer=function() return nil end
local effects=engine.effects({{handle=sword}})
assert(#effects==1 and effects[1]=='NS_A_Weapon_Signal_Sword[NiagaraComponent]@Sword:FX_WeaponFX_Begin',effects[1])
-- EVE's own effect components join the walk unless it is attached only: the pose stream walks them on their own turn.
local owned=effect('NC3','NS_PC_SpinTrail_01',true,'None')
package.loaded.UEHelpers.GetPlayer=function()
    return object('EVE',{K2_GetComponentsByClass=function(_,class) return tarray(class.name=='NiagaraComponent' and {owned} or {}) end})
end
local find_class=StaticFindObject
StaticFindObject=function(path) local c=object('Class'); c.name=path:match('%.(%w+)$'); return c end
assert(#engine.effects({{handle=sword}})==2 and #engine.effects({{handle=sword}},true)==1,'Attached only leaves out her own')
local own=engine.effects({})
assert(#own==1 and own[1]=='NS_PC_SpinTrail_01[NiagaraComponent]@Sword:None',own[1])
StaticFindObject,package.loaded.UEHelpers.GetPlayer=find_class,function() return nil end
-- Known effect assets: all loaded ones, or with an index only that path; with how many paths there are.
local looked_for={}
StaticFindObject=function(path) looked_for[#looked_for+1]=path; return path:find('NS_A_Weapon_Signal_Sword',1,true) and object('NS_A_Weapon_Signal_Sword') or nil end
local all,paths=engine.known_effects()
assert(paths==22 and #looked_for==22 and #all==1 and all[1].name=='NS_A_Weapon_Signal_Sword','All paths: '..#looked_for)
looked_for={}
local one,same=engine.known_effects(8)
assert(same==22 and #looked_for==1 and looked_for[1]:find('NS_A_Weapon_Signal_Sword',1,true) and #one==1,'Only path 8: '..tostring(looked_for[1]))
StaticFindObject=find_class
-- Shows on EVE: a lifted hit-level check is restored right after the call (the build reads it there), a lifted
-- stop-at-move only through the returned restore; a failed call restores both.
local SHOW_PATH='/Game/Art/Show/Result/Result_Guard_Back.Result_Guard_Back'
local key=object('SBShowAnimKey_0',{bCheckHitLevel=true,bStopAtMove=true})
local seen,refuse
local eve=object('EVE')
function eve:IsA(class) return class.name=='SBCharacter' end
function eve:K2_GetActorLocation() return {X=0,Y=0,Z=0} end
function eve:K2_GetActorRotation() return {Pitch=0,Yaw=0,Roll=0} end
function eve:PlaySingleShow(path,full,at)
    seen={path=path,full=full,gate=key.bCheckHitLevel,stop=key.bStopAtMove,y=at.Y}
    if refuse then error('refused') end
    return true
end
package.loaded.UEHelpers.GetPlayerController=function() return object('Controller',{K2_GetPawn=function() return eve end}) end
StaticFindObject=function(path)
    if path=='/Script/SB.SBCharacter' then local c=object('Class'); c.name='SBCharacter'; return c end
    if path==SHOW_PATH then return object('Result_Guard_Back') end
    if path==SHOW_PATH..':SBShowAnimKey_0' then return key end
    return nil
end
local info,restore=engine.play_show(SHOW_PATH,90,nil,'SBShowAnimKey_0')
assert(seen.gate==false and seen.full==true and seen.y==300 and key.bCheckHitLevel==true and restore==nil,info)
assert(info:find('toward the right (90 deg), hit-level check lifted',1,true),info)
info,restore=engine.play_show(SHOW_PATH,nil,'SBShowAnimKey_0')
assert(seen.stop==false and key.bStopAtMove==false and restore,info)
restore()
assert(key.bStopAtMove==true,'The stop-at-move comes back through the restore')
refuse=true
assert(not pcall(engine.play_show,SHOW_PATH,0,'SBShowAnimKey_0','SBShowAnimKey_0'))
assert(key.bStopAtMove==true and key.bCheckHitLevel==true,'A refused play restores both keys')
-- Hit reactions: the gate lifts, until the restore, the hit-level check of the key Stellar Blade picks for the attack's
-- angle in the Show asked for (the strong Show's front key reaches 50 degrees, the light one's 45); a strong Show that
-- cannot be loaded falls back to the light one.
FName=function(name) return name end
local HIT='/Game/Art/Show/Hit/'
local LIGHT,STRONG=HIT..'Result_Hit_Stand_LightAttack.Result_Hit_Stand_LightAttack',HIT..'Result_Hit_Stand_StrongAttack.Result_Hit_Stand_StrongAttack'
local hitObjects,strongLoaded,libraryShow,played={},true,nil,nil
for _,path in ipairs({LIGHT,STRONG}) do
    hitObjects[path]=object(path:match('[^.]+$'))
    for _,name in ipairs({'SBShowAnimKey_1','SBShowAnimKey_2'}) do hitObjects[path..':'..name]=object(name,{bCheckHitLevel=true}) end
end
StaticFindObject=function(path)
    if path=='/Script/SB.SBCharacter' then local c=object('Class'); c.name='SBCharacter'; return c end
    if path=='/Script/SB.Default__SBBlueprintLibrary' then return object('Library',{PlaySBShowToPlayerCharacter=function(_,show) libraryShow=show end}) end
    if path:find(STRONG,1,true)==1 and not strongLoaded then return nil end
    return hitObjects[path]
end
eve.Mesh=object('Mesh',{GetSocketTransform=function() return {Translation={X=0,Y=0,Z=0}} end})
function eve:PlaySingleShow(path)
    played={path=path}
    for name,o in pairs(hitObjects) do if o.bCheckHitLevel==false then played.lifted=name end end
end
local function react(yaw,method,strong)
    local hitInfo,sample=engine.hit_reaction(yaw,method,strong)
    local lifted=played and played.lifted
    engine.hit_restore(sample)
    for name,o in pairs(hitObjects) do assert(o.bCheckHitLevel~=false,name..' stays lifted') end
    return hitInfo,lifted
end
local hitInfo,lifted=react(47,'gate',true)
assert(played.path==STRONG and lifted==STRONG..':SBShowAnimKey_2'
    and hitInfo=='gate SBShowAnimKey_2, attack from the right (47 deg), Result_Hit_Stand_StrongAttack',hitInfo)
hitInfo,lifted=react(47,'gate',false)
assert(played.path==LIGHT and lifted==LIGHT..':SBShowAnimKey_1' and hitInfo:find('Result_Hit_Stand_LightAttack$'),hitInfo)
hitInfo,lifted=react(-120,'gate',true)
assert(lifted==STRONG..':SBShowAnimKey_1' and hitInfo:find('from the left (-120 deg)',1,true),hitInfo)
react(0,'library',true)
assert(libraryShow==hitObjects[STRONG],'The library plays the strong Show')
strongLoaded=false
hitInfo,lifted=react(47,'gate',true)
assert(played.path==LIGHT and lifted==LIGHT..':SBShowAnimKey_1'
    and hitInfo:find('Result_Hit_Stand_LightAttack (strong Show unavailable: ',1,true) and hitInfo:find(STRONG..' is not loaded)',1,true),hitInfo)
local unseen=effect('NC3','NS_A_LensFlare_Drone',true,'FX_Lens')
unseen.IsVisible=function() return false end
sword.AttachChildren=tarray({unseen})
assert(#engine.effects({{handle=sword}})==0,'An active effect Stellar Blade does not draw is not one')
-- Identities: EVE's components, then her drone's (the nearest of tribe Drone) as Drone.<name>, found at most every 5 s.
local function at(x) return {X=x,Y=0,Z=0} end
local meshClass=object('SBSkeletalMeshComponent'); meshClass.GetAddress=function() return 0x7f0000030000 end
local function skin(name,mesh,leader,visible)
    local c=object(name,{SkeletalMesh=object(mesh),MasterPoseComponent={Get=function() return leader end}})
    c.GetAddress=function() return 0x7f0000040000+#name end
    c.GetClass=function() return meshClass end
    c.IsVisible=function() return visible end
    return c
end
local eveBody=skin('CharacterMesh0','CH_P_EVE_52_TypeB',nil,true)
local eve=object('EVE'); eve.K2_GetActorLocation=function() return at(0) end
function eve:K2_GetComponentsByClass() return {eveBody,skin('Mesh_Face','CH_P_EVE_Face_Jali3',eveBody,true)} end
local droneClass,otherClass=object('CH_Drone_BP_C'),object('CH_Drone_BP_Seq_C')
local function drone(name,tribe,x,class)
    local d=object(name,{Tribe=fname(tribe),bHidden=false}); d.GetClass=function() return class or droneClass end
    d.K2_GetActorLocation=function() return wrap(at(x)) end
    local body=skin('CharacterMesh0','CH_NPC_Drone',nil,false)
    function d:K2_GetComponentsByClass() return tarray({body}) end
    return d
end
local searches,drones=0,{drone('Far','Drone',900),drone('Spawned','None',10),drone('Near','Drone',200),drone('Sequence','Drone',5,otherClass)}
FindAllOf=function(name) assert(name=='CH_Drone_BP_C'); searches=searches+1; return drones end
package.loaded.UEHelpers.GetPlayer=function() return eve end
StaticFindObject=function(path) -- The Show section above replaced it.
    assert(path=='/Script/Engine.SkeletalMeshComponent'); local c=object('Class'); c.name='SkeletalMeshComponent'; return c
end
local fresh=require('pose_adapter').new()
local ids,state=fresh.identities()
assert(#ids==3 and ids[1].name=='CharacterMesh0' and not ids[1].drone and ids[2].leader=='CharacterMesh0','EVE first')
assert(ids[3].name=='Drone.CharacterMesh0' and ids[3].drone and ids[3].mesh_name=='CH_NPC_Drone' and not ids[3].leader)
assert(state:find('^Near actor shown, mesh invisible %(found in [%d.]+ ms%)$'),state)
fresh.identities()
assert(searches==1,'A valid drone is kept')
-- A drone follower leads by its prefixed leader name.
local led=drone('Led','Drone',50)
local ledBody=skin('CharacterMesh0','CH_NPC_Drone',nil,true)
function led:K2_GetComponentsByClass() return tarray({ledBody,skin('Gun','CH_NPC_Drone_Gun',ledBody,true)}) end
local saved=drones
drones={led}
local leading=require('pose_adapter').new().identities()
assert(leading[4].name=='Drone.Gun' and leading[4].leader=='Drone.CharacterMesh0','The follower names its prefixed leader')
drones=saved
-- The drone found goes: the next call looks again at once, not after the wait.
local before=searches
drones[3].IsValid=function() return false end
fresh.identities()
assert(searches==before+1,'A drone that went is looked for again at once')
drones={}
local clock,real_clock=100,os.clock
os.clock=function() return clock end
local alone=require('pose_adapter').new()
before=searches
local only,none=alone.identities()
assert(#only==2 and none=='none' and searches==before+1)
clock=104; alone.identities()
assert(searches==before+1,'No drone: the next search waits')
clock=105.5; alone.identities()
assert(searches==before+2,'Then searches again after 5 s')
os.clock=real_clock
FindAllOf=function() error('FindAllOf unavailable') end
local failing,why=require('pose_adapter').new().identities()
assert(#failing==2 and why:find('unavailable: ',1,true),'A failing drone search leaves EVE streaming')
-- The lock's sword Shows: the unfold and fold Shows of the row EquipmentDataList names at EquipmentType_Weapon (1, Lua index
-- 2; the list follows ESBEquipmentType, so index 6 is a hair row), their
-- NonPonyTail copies for LightSword1 while the ponytail is hidden, LightSword1's when the row is unknown, unreadable or
-- its Show does not play; the character draw and sheathe Shows stay.
local SHOW,WEAPON='/Game/Art/Show/','/Game/Art/Show/Weapon/Eve/'
local DRAW,SHEATHE=SHOW..'CH_P_EVE_01/Basic/Eve_Drawing_Moving_LockOn.Eve_Drawing_Moving_LockOn',SHOW..'CH_P_EVE_01/Basic/Eve_Swording_Moving.Eve_Swording_Moving'
local blade=object('Weapon1',{state='Sword',socket='SC_SwordingPoint'})
function blade:GetCustomAnimStateName() return fname(self.state) end
function blade:GetAttachSocketName() return fname(self.socket) end
local function slots(row) local list={}; for i=1,16 do list[i]=fname('None') end; list[2]=wrap(fname(row)); list[6]=wrap(fname('CH_P_EVE_Hair')); return list end
local holder=object('EVE',{EquipmentDataList=slots('LightSword1'),bHidePonyTail=false,bForceFoldWeapon=false,bForceFoldWeaponStance=false,bForceWeaponHide=false})
function holder:IsA(class) return class.name=='SBCharacter' end
function holder:IsBattle() return false end
function holder:GetSBSkeletalMeshComponent(slot) assert(slot==5); return blade end
function holder:K2_GetActorLocation() return {X=0,Y=0,Z=0} end
function holder:K2_GetActorRotation() return {Pitch=0,Yaw=0,Roll=0} end
local played,missing={},{}
function holder:PlaySingleShow(path) played[#played+1]=path end
package.loaded.UEHelpers.GetPlayer=function() return holder end
package.loaded.UEHelpers.GetPlayerController=function() return object('Controller',{K2_GetPawn=function() return holder end}) end
StaticFindObject=function(path)
    if path=='/Script/SB.SBCharacter' then local c=object('Class'); c.name='SBCharacter'; return c end
    return not missing[path] and object(path:match('[^.]+$')) or nil
end
local swords=require('pose_adapter').new()
local function draw()
    played={}; blade.state='Sword'
    local line=swords.lock_draw(false)
    assert(played[1]==DRAW and #played==2,line)
    return played[2],line
end
local path,line=draw()
assert(path==WEAPON..'Eve_UnFoldSword.Eve_UnFoldSword','LightSword1 with the ponytail: the stock unfold')
assert(line=='drawing with Eve_UnFoldSword [LightSword1, ponytail shown] (sword was Sword@SC_SwordingPoint)',line)
holder.bHidePonyTail=true
path,line=draw()
assert(path==WEAPON..'NonPonyTail/Eve_UnFoldSword_NonTail.Eve_UnFoldSword_NonTail',path)
assert(line:find('with Eve_UnFoldSword_NonTail [LightSword1, ponytail hidden]',1,true),line)
holder.EquipmentDataList=slots('Eve_Fusion_Sword')
path,line=draw()
assert(path==WEAPON..'Eve_UnFoldSword_Fusion.Eve_UnFoldSword_Fusion','The Fusion pair has no NonPonyTail copy: '..path)
blade.state='Draw'; played={}
line=swords.lock_sheathe()
assert(played[1]==WEAPON..'Eve_FoldSword_Fusion.Eve_FoldSword_Fusion' and played[2]==SHEATHE and #played==2,line)
assert(line=='sheathing with Eve_FoldSword_Fusion [Eve_Fusion_Sword, ponytail hidden] (sword was Draw@SC_SwordingPoint)',line)
holder.EquipmentDataList=slots('Skull_Sword'); holder.bHidePonyTail=wrap(false)
path,line=draw()
assert(path==WEAPON..'Eve_UnFoldSword.Eve_UnFoldSword' and line:find('[weapon Skull_Sword unknown, stock pair, ponytail shown]',1,true),line)
holder.EquipmentDataList={}; holder.bHidePonyTail=nil
setmetatable(holder,{__index=function(_,key) if key=='bHidePonyTail' then error('no such property') end end})
path,line=draw()
assert(path==WEAPON..'Eve_UnFoldSword.Eve_UnFoldSword' and line:find('[weapon unread unknown, stock pair, ponytail unread]',1,true),line)
setmetatable(holder,nil)
holder.EquipmentDataList=slots('LightSword1'); holder.bHidePonyTail=true
missing[WEAPON..'NonPonyTail/Eve_UnFoldSword_NonTail.Eve_UnFoldSword_NonTail']=true
path,line=draw()
assert(path==WEAPON..'Eve_UnFoldSword.Eve_UnFoldSword','A copy that does not play falls back to the stock Show')
assert(line:find('with Eve_UnFoldSword [LightSword1, ponytail hidden; Eve_UnFoldSword_NonTail failed: ',1,true),line)
missing[WEAPON..'Eve_UnFoldSword.Eve_UnFoldSword']=true
played={}; blade.state='Sword'
local drew,err=pcall(swords.lock_draw,false)
assert(not drew and tostring(err):find('Eve_UnFoldSword.Eve_UnFoldSword is not loaded',1,true),'No Show at all is an error: '..tostring(err))
-- The lock: follow clears Stellar Blade's soft lock-rotation disable only while EVE is locked on the proxy.
local proxy=object('Proxy',{UnavailableLockOn=0})
function proxy:K2_SetActorLocation(at) self.at=at end
function proxy:K2_GetActorLocation() return {X=0,Y=100,Z=0} end
local lockedOn=true
function holder:IsLockOn() return lockedOn end
function holder:GetVelocity() return {X=0,Y=0,Z=0} end
holder.LockOnCharacter,holder.HideTimeForLockOnCharacter,holder.DisableLockOnCharacterRotaion=proxy,5,0
holder.bSoftDisableLockOnCharacterRotation=true
local locked,cleared=swords.lock_follow(proxy,{X=1,Y=2,Z=3})
assert(locked==true and cleared==true and holder.bSoftDisableLockOnCharacterRotation==false and proxy.at.Z==3
    and holder.HideTimeForLockOnCharacter==0,'Locked on the proxy: the soft disable is cleared')
locked,cleared=swords.lock_follow(proxy,{X=1,Y=2,Z=3})
assert(locked==true and cleared==false,'Nothing to clear')
holder.bSoftDisableLockOnCharacterRotation,holder.LockOnCharacter=true,object('Cow')
locked,cleared=swords.lock_follow(proxy,{X=1,Y=2,Z=3})
assert(locked==false and cleared==false and holder.bSoftDisableLockOnCharacterRotation==true,'Not while locked on another target')
holder.LockOnCharacter,lockedOn=proxy,false
locked,cleared=swords.lock_follow(proxy,{X=1,Y=2,Z=3})
assert(locked==false and cleared==false and holder.bSoftDisableLockOnCharacterRotation==true,'Nor while not locked on')
holder.bSoftDisableLockOnCharacterRotation,lockedOn,holder.DisableLockOnCharacterRotaion=false,true,2
local status=swords.lock_status(proxy)
assert(status:find('facing -90, camera n/a deg off target; dist 100 cm',1,true) and status:find('; soft false rotoff 2$'),status)
-- LockTrace samples: unreadable values are nil, not errors.
holder.bRunningSkill,holder.LastMoveFowardInput=true,0.5
setmetatable(holder,{__index=function(_,key) if key=='bIdleTurning' then error('no such property') end end})
local sample=swords.lock_trace(proxy)
setmetatable(holder,nil)
assert(sample.facing==-90 and sample.yaw==0 and sample.tx==0 and sample.ty==100 and sample.rotoff==2 and sample.soft==false
    and sample.skill==true and sample.turning==nil and sample.inf==0.5 and sample.inr==nil,'A trace sample')
-- The engage's neighbours: Stellar Blade characters within the radius, horizontally, but EVE and the proxy.
local function standing(name,x,y,z) return object(name,{Tribe=fname('Enemy'),bHidden=false,K2_GetActorLocation=function() return {X=x,Y=y,Z=z} end}) end
FindAllOf=function(name) assert(name=='SBCharacter'); return {holder,proxy,standing('Near',0,1000,-2000),standing('Far',4000,0,0)} end
local near=swords.lock_neighbours(proxy,3500)
assert(near=='Near tribe Enemy at 0 1000 -2000 (1000 cm, bearing 90, hidden false)',near)
FindAllOf=function() return {holder,proxy} end
assert(swords.lock_neighbours(proxy,3500)=='none within 3500 cm')
-- Stellar Blade's HUD widgets: item slots by name, the dash slot and bullet slots by class, never their designer
-- templates; kept while valid; while one is missing, searched again 2 s later, doubling up to 30 s (2 s again once a
-- search finds one), at once when one found went. A slot's texture is its thumbnail's, else the Icon_Texture its general
-- child's dynamic material sets.
local LIVE,TEMPLATE='/Engine/Transient.GameEngine_1:SBGameInstance_C_1.WB_MainHUD_Master_C_7.WidgetTree.','/Game/Art/UI/Widget/HUD/WB_MainHUD_Master.WB_MainHUD_Master_C:WidgetTree.'
local function widget(name,fields,path) local o=object(name,fields); o.GetFullName=function() return 'Widget '..(path or LIVE)..name end; return o end
local function typed(name,class,fields) local o=object(name,fields); o.GetClass=function() return object(class) end; return o end
local function thumb(resource) return object('Image_Item_Thumb',{Brush=wrap({ResourceObject=resource})}) end
local function general(resource) return object('WB_MainHUD_ItemSlotGeneral',{Image_Item_Thumb=thumb(resource)}) end
local function material(class,textures) -- textures: {{parameter, texture}, ...} in array order.
    local values={}
    for _,t in ipairs(textures) do values[#values+1]={ParameterInfo={Name=fname(t[1])},ParameterValue=typed(t[2],'Texture2D')} end
    return typed(class..'_0',class,{TextureParameterValues=tarray(values)})
end
-- The heal slot shows the Tumbler's cup, which has no thumbnail; the util slot's general child draws the Camera.
local healSlot=widget('WB_MainHUD_HealItem',{ItemCount=3,RecoveryPotionMaxCount=5,ChangeItemCategory=12,CoolTimePercent=0,UseActive=true,
    Image_Item_Thumb=thumb(nil),WB_MainHUD_ItemSlotGeneral=general(material('MaterialInstanceDynamic',{}))})
local utilSlot=widget('WB_MainHUD_UtilItem',{ItemCount=wrap(2),CoolTimePercent=0.4,Image_Item_Thumb=thumb(nil),
    WB_MainHUD_ItemSlotGeneral=general(material('MaterialInstanceDynamic',{{'Mask_Texture','Mask'},{'Icon_Texture','Icon_Camera'},{'Noise_Texture','Noise'}}))})
setmetatable(utilSlot,{__index=function(_,key) if key=='UseActive' then error('no such property') end end})
local lowerSlot=widget('WB_MainHUD_Lower1Item',{ItemCount=0,WB_MainHUD_ItemSlotGeneral=general(material('MaterialInstanceConstant',{{'Icon_Texture','OrganicCork'}}))})
local dashSlot=widget('WB_Dash_Slot',{CoolTimeProgress=0.25})
local bulletSlots={widget('Slot0',{ItemAlias=fname('Bullet_Scatter'),ItemCount=8,CooltimePercent=0}),
    widget('Slot1',{ItemAlias=wrap(fname('Bullet_Normal')),ItemCount=120,CooltimePercent=0.5})}
local instances={WB_MainHUD_ItemSlot_C={widget('WB_MainHUD_HealItem',{ItemCount=5},TEMPLATE),widget('WB_MainHUD_HealItem_Old',{}),healSlot,utilSlot},
    WB_MainHUD_Dash_Slot_C={widget('WB_MainHUD_Dash_Slot',{CoolTimeProgress=1},TEMPLATE),dashSlot},
    WB_Tps_Bullet2_Slot_C={widget('Slot_Center1',{ItemCount=-1,CooltimePercent=1},'/Game/Art/UI/Widget/TPS/WB_TPs_Bullet2.WB_TPs_Bullet2_C:WidgetTree.')}}
local walks={}
FindAllOf=function(class) walks[class]=(walks[class] or 0)+1; return instances[class] end
local shooter=object('EVE',{CurrentBulletItemAlias=fname('Bullet_Normal')})
package.loaded.UEHelpers.GetPlayer=function() return shooter end
clock=100; os.clock=function() return clock end
local widgets=require('pose_adapter').new()
local function walked() local n=0; for _,count in pairs(walks) do n=n+count end; return n end
local hud=widgets.hud()
assert(hud.search and hud.search.missing=='lower, bullets' and hud.search.retry==2 and hud.search.ms==0,'The first call searches: '..tostring(hud.search and hud.search.missing))
assert(hud.found_util==true,'The search found the util widget')
assert(hud.heal.thumb==nil and hud.heal.count==3 and hud.heal.max==5 and hud.heal.category==12 and hud.heal.cooldown==0 and hud.heal.usable==true,
    'The live heal slot, not its template')
assert(hud.util.thumb=='Icon_Camera' and hud.util.count==2 and hud.util.cooldown==0.4 and hud.util.usable==nil,'The material\'s icon; unreadable values are nil')
assert(hud.lower==nil and hud.dash==0.25 and hud.bullet=='Bullet_Normal' and hud.ammo==nil,'No bullet slot, no ammo; the live dash slot')
local searches={}
for _,t in ipairs({101,101.9,102,105.9,106,113.9,114,129.9,130,159.9,160}) do
    clock=t
    local before=walked()
    if widgets.hud().search then searches[#searches+1]=t end
    assert((walked()>before)==(searches[#searches]==t),'A search walks the missing classes')
end
assert(table.concat(searches,' ')=='102 106 114 130 160','A missing widget is searched for 2, 4, 8, 16 then 30 s later: '..table.concat(searches,' '))
assert(walks.WB_MainHUD_Dash_Slot_C==1,'A found class is not walked again')
instances.WB_MainHUD_ItemSlot_C[#instances.WB_MainHUD_ItemSlot_C+1]=lowerSlot
clock=190; hud=widgets.hud()
assert(hud.search.missing=='bullets' and hud.search.retry==2 and hud.lower.count==0 and hud.found_util==false,'A search that finds one waits 2 s again')
assert(hud.lower.thumb==nil,'Another material\'s Icon_Texture is no item')
table.move(bulletSlots,1,#bulletSlots,2,instances.WB_Tps_Bullet2_Slot_C)
clock=192; hud=widgets.hud()
assert(hud.search.missing=='none' and hud.search.retry==nil and walks.WB_MainHUD_ItemSlot_C==7,'Everything found, the item slots not walked again')
assert(hud.ammo.count==120 and hud.ammo.cooldown==0.5,'The selected bullet\'s slot gives the ammo')
local before=walked()
clock=193; assert(widgets.hud().search==nil and walked()==before,'Found widgets are kept')
utilSlot.IsValid=function() return false end
instances.WB_MainHUD_ItemSlot_C={healSlot,lowerSlot}
hud=widgets.hud()
assert(hud.search and hud.search.missing=='util' and hud.search.retry==2 and hud.util==nil and hud.found_util==false,'A widget that went is searched for at once')
assert(walks.WB_MainHUD_ItemSlot_C==8 and walks.WB_MainHUD_Dash_Slot_C==1 and walks.WB_Tps_Bullet2_Slot_C==8,'Only its class is walked')
instances.WB_MainHUD_ItemSlot_C={healSlot,lowerSlot,widget('WB_MainHUD_UtilItem',{ItemCount=0,Image_Item_Thumb=thumb(typed('Grenade','Texture2D')),
    WB_MainHUD_ItemSlotGeneral=general(material('MaterialInstanceDynamic',{{'Icon_Texture','TrapGrenade'}}))})}
clock=195; hud=widgets.hud()
assert(hud.found_util==true and hud.util.count==0 and hud.util.thumb=='Grenade','A new util widget is flagged; a thumbnail texture comes first')
clock=196; assert(widgets.hud().found_util==false,'Only in the call that found it')
local hooked
RegisterHook=function(path,fn) hooked={path=path,fn=fn} end
widgets.watch_consume(print)
assert(hooked.path=='/Script/SB.SBNetworkPlayerController:ServerRequest_ItemBucketItemConsume' and hooked.fn==print)
os.clock=real_clock
print('PASS pose adapter: wrapped component lists, TArray structs, weak leaders, socket fallback, failing components, glow parameters, drawn attached effects, Show key lifts, hit reactions by strength, the drone, the sword Shows per weapon and hairstyle, the lock soft-disable clear, status, trace and neighbours, and the live HUD widgets with their thumbnail or material textures')
