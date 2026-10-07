-- Game-thread UE4SS access for the pose probe; returns plain Lua numbers and strings.
local UE=require('UEHelpers')
local Adapter={}
local PARENT_BONE_SPACE=3 -- ERelativeTransformSpace::RTS_ParentBoneSpace
local function kind(value)
    if type(value)~='userdata' and type(value)~='table' then return nil end
    local ok,result=pcall(function() return value:type() end)
    return ok and result or nil
end
-- UE4SS may wrap values as RemoteUnrealParam/LocalUnrealParam; open them before use.
local function value_of(value)
    for _=1,3 do
        local k=kind(value)
        if k~='RemoteUnrealParam' and k~='LocalUnrealParam' then return value end
        value=value:get()
    end
    return value
end
local function valid(object)
    object=value_of(object)
    return object~=nil and kind(object)~=nil and object:IsValid() and object or nil
end
-- Arrays arrive as converted Lua lists or TArray userdata; elements may be wrapped.
local function each(array,callback)
    array=value_of(array)
    if array==nil then return end
    if type(array)=='table' and rawget(array,'ForEach')==nil then
        local wrapped
        for index,value in ipairs(array) do
            if wrapped==nil then local k=kind(value); wrapped=k=='RemoteUnrealParam' or k=='LocalUnrealParam' end
            callback(index,wrapped and value_of(value) or value)
        end
        return
    end
    array:ForEach(function(index,element) callback(index,value_of(element:get())) end)
end
-- A reflected value pointer (light userdata in UE4SS) as an integer address.
local function address(pointer)
    if math.type(pointer)=='integer' then return pointer end
    return tonumber((string.format('%p',pointer):gsub('^0[xX]','')),16)
end
local function text(name)
    name=value_of(name)
    return type(name)=='string' and name or name:ToString()
end
local function transform(t)
    local r,p,s=t.Rotation,t.Translation,t.Scale3D
    if r==nil or type(r.X)~='number' then -- Wrapped values are rare; keep the per-bone path cheap.
        t=value_of(t); r,p,s=value_of(t.Rotation),value_of(t.Translation),value_of(t.Scale3D)
    end
    return {qx=r.X,qy=r.Y,qz=r.Z,qw=r.W,x=p.X,y=p.Y,z=p.Z,sx=s.X,sy=s.Y,sz=s.Z}
end
local function describe(component)
    local mesh=valid(component.SkeletalMesh)
    local entry={handle=component,name=text(component:GetFName()),mesh=mesh and text(mesh:GetFName()) or 'none',
        bones=component:GetNumBones(),morphs={}}
    local found,leader=pcall(function() return valid(value_of(component.MasterPoseComponent):Get()) end)
    if found and leader then entry.leader=text(leader:GetFName()) elseif not found then entry.note='leader unknown: '..tostring(leader) end
    local parent=valid(component:GetAttachParent())
    entry.parent=parent and text(parent:GetFName()) or nil
    entry.socket=text(component:GetAttachSocketName())
    if mesh then each(mesh.MorphTargets,function(_,morph) morph=valid(morph); if morph then entry.morphs[#entry.morphs+1]=text(morph:GetFName()) end end) end
    return entry
end
function Adapter.new()
    local engine,fallback={},{}
    function engine.components()
        local pawn=valid(UE.GetPlayer())
        assert(pawn,'Load a playable save before probing EVE.')
        local class=valid(StaticFindObject('/Script/Engine.SkeletalMeshComponent'))
        assert(class,'SkeletalMeshComponent is unavailable in this game build.')
        local list={}
        each(pawn:K2_GetComponentsByClass(class),function(index,component)
            component=valid(component)
            if not component then return end
            local ok,entry=pcall(describe,component)
            -- A component whose description fails is listed but never snapshotted.
            list[#list+1]=ok and entry or {handle=component,name='component'..index,mesh='?',bones=0,morphs={},leader='unknown',note=tostring(entry)}
        end)
        return list
    end
    local function sockets(component)
        -- Fallback: one reflected call per bone and per morph.
        local result={names={},transforms={},weights={},method='sockets'}
        for index=0,component:GetNumBones()-1 do
            local name=value_of(component:GetBoneName(index))
            result.names[index+1]=text(name)
            result.transforms[index+1]=transform(component:GetSocketTransform(name,PARENT_BONE_SPACE))
        end
        local mesh=valid(component.SkeletalMesh)
        if mesh then
            each(mesh.MorphTargets,function(_,morph)
                morph=valid(morph)
                if morph then result.weights[#result.weights+1]=component:GetMorphTarget(morph:GetFName()) end
            end)
        end
        return result
    end
    function engine.snapshot(component)
        assert(valid(component),'Skeletal mesh component is no longer valid.')
        local key=component:GetAddress()
        if fallback[key] then return sockets(component) end
        local ran,result=pcall(function()
            local snapshot={}
            local returned=component:SnapshotPose(snapshot)
            -- Out structs normally fill the passed table; accept a returned struct too.
            if snapshot.LocalTransforms==nil and returned~=nil then snapshot=value_of(returned) end
            assert(value_of(snapshot.bIsValid),'SnapshotPose returned an invalid pose.')
            local value={names={},transforms={},weights={},method='snapshot'}
            each(snapshot.BoneNames,function(index,name) value.names[index]=text(name) end)
            each(snapshot.LocalTransforms,function(index,t) value.transforms[index]=transform(t) end)
            if snapshot.MorphTargetWeights~=nil then each(snapshot.MorphTargetWeights,function(index,weight) value.weights[index]=weight end) end
            assert(#value.names>0 and #value.names==#value.transforms,'SnapshotPose returned mismatched arrays.')
            return value
        end)
        if ran then return result end
        fallback[key]=tostring(result)
        local value=sockets(component)
        value.fallback=fallback[key]
        return value
    end
    function engine.world(component) return transform(component:K2_GetComponentToWorld()) end
    -- Pose stream: cheap identities every check, names only when the composition changes.
    local function skeletal(actor,class,prefix,list)
        each(actor:K2_GetComponentsByClass(class),function(_,component)
            component=valid(component)
            local mesh=component and valid(component.SkeletalMesh)
            if not mesh then return end
            local found,leader=pcall(function() return valid(value_of(component.MasterPoseComponent):Get()) end)
            list[#list+1]={handle=component,name=prefix..text(component:GetFName()),object=component:GetAddress(),
                class=valid(component:GetClass()):GetAddress(),mesh=mesh:GetAddress(),mesh_name=text(mesh:GetFName()),
                leader=found and leader and prefix..text(leader:GetFName()) or nil,mesh_handle=mesh}
        end)
        return list
    end
    -- EVE's drone: its own Stellar Blade character (CH_Drone_BP_C, tribe Drone; CharacterMesh0 carries CH_NPC_Drone or
    -- the equipped skin) that follows her, scans and turns into her gun. The nearest of its tribe, kept while valid and
    -- looked for again at most every DRONE_RETRY seconds (FindAllOf walks every object), at once when the one found went.
    local DRONE_RETRY=5
    local drone,drone_search,drone_ms=nil,-math.huge,0
    local function find_drone(pawn)
        if valid(drone) then return drone end
        if drone~=nil then drone_search=-math.huge end -- It went: another may already stand in.
        drone=nil
        if os.clock()-drone_search<DRONE_RETRY then return nil end
        drone_search=os.clock()
        local here,best=value_of(pawn:K2_GetActorLocation()),math.huge
        for _,o in ipairs(FindAllOf('CH_Drone_BP_C') or {}) do
            o=valid(o)
            if o and text(valid(o:GetClass()):GetFName())=='CH_Drone_BP_C' and text(o.Tribe)=='Drone' then
                local at=value_of(o:K2_GetActorLocation())
                local d=(at.X-here.X)^2+(at.Y-here.Y)^2+(at.Z-here.Z)^2
                if d<best then drone,best=o,d end
            end
        end
        drone_ms=(os.clock()-drone_search)*1000
        return drone
    end
    -- EVE's skeletal mesh components, then her drone's as 'Drone.<name>' (EVE's leader names stay unique), marked drone;
    -- also a label of the drone's state for the log: its object and whether its actor or its mesh is hidden.
    function engine.identities()
        local pawn=valid(UE.GetPlayer())
        assert(pawn,'Load a playable save before streaming EVE.')
        local class=valid(StaticFindObject('/Script/Engine.SkeletalMeshComponent'))
        assert(class,'SkeletalMeshComponent is unavailable in this game build.')
        local list=skeletal(pawn,class,'',{})
        local ok,found,state=pcall(function()
            local d=find_drone(pawn)
            if not d then return {},'none' end
            local parts=skeletal(d,class,'Drone.',{})
            local shown=parts[1] and parts[1].handle:IsVisible()
            return parts,string.format('%s actor %s, mesh %s (found in %.1f ms)',text(d:GetFName()),d.bHidden and 'hidden' or 'shown',
                parts[1] and (shown and 'visible' or 'invisible') or 'none',drone_ms)
        end)
        for _,c in ipairs(ok and found or {}) do c.drone=true; list[#list+1]=c end
        return list,ok and state or 'unavailable: '..tostring(found)
    end
    -- The local player controller, the byte offset of its ControlRotation (Pitch, Yaw, Roll floats), its pawn
    -- and its look input scales.
    function engine.controller()
        local controller=valid(UE.GetPlayerController())
        if not controller then return nil end
        local property=controller:Reflection():GetProperty('ControlRotation')
        local object=controller:GetAddress()
        local offset=address(property:ContainerPtrToValuePtr(controller,0))-object
        assert(offset>0 and offset<0x4000 and offset%4==0,'Unexpected ControlRotation offset.')
        local pawn=valid(controller:K2_GetPawn())
        assert(pawn,'The player controller has no pawn.')
        return {object=object,class=valid(controller:GetClass()):GetAddress(),offset=offset,
            pawn=pawn:GetAddress(),pawn_class=valid(pawn:GetClass()):GetAddress(),
            yaw_scale=controller.InputYawScale,pitch_scale=controller.InputPitchScale}
    end
    -- Glow parameters the game animates on dynamic material instances (Stellar Blade's material-parameter notifies),
    -- with their float count: the native adapter reads them each frame by array element and name.
    local GLOW={Opacity=1,Emissive_Intensity=1,Emissive_Intensity_2=1,Emissive_Color=4,Emissive_Color_2=4}
    local ARRAYS={{'ScalarParameterValues',1},{'VectorParameterValues',4}}
    function engine.parameters(list)
        local found={}
        for index,entry in ipairs(list) do
            local component=entry.handle
            local slots={}
            each(component:GetMaterialSlotNames(),function(k,name) slots[k]=text(name) end)
            for m=0,component:GetNumMaterials()-1 do
                local mid=valid(component:GetMaterial(m))
                if mid and text(valid(mid:GetClass()):GetFName())=='MaterialInstanceDynamic' then
                    for _,array in ipairs(ARRAYS) do
                        local property=mid:Reflection():GetProperty(array[1])
                        local offset=address(property:ContainerPtrToValuePtr(mid,0))-mid:GetAddress()
                        assert(offset>0 and offset<0x4000 and offset%8==0,'Unexpected material parameter array offset.')
                        each(mid[array[1]],function(element,value)
                            local name=value_of(value_of(value.ParameterInfo).Name)
                            local label=text(name)
                            if GLOW[label]==array[2] then
                                found[#found+1]={component=index,slot=slots[m+1] or tostring(m),name=label,object=mid:GetAddress(),
                                    class=valid(mid:GetClass()):GetAddress(),offset=offset,element=element-1,
                                    index=name:GetComparisonIndex(),floats=array[2]}
                            end
                        end)
                    end
                end
            end
        end
        return found
    end
    -- Active particle effects (Cascade and Niagara components) on EVE: owned by her actor, or pooled by the world and
    -- attached to her skeletal components (up to three levels down). Returns log labels (asset, class, attach parent,
    -- socket) and the effect assets seen, as {name=, object=}.
    local EFFECT_CLASSES={'/Script/Engine.ParticleSystemComponent','/Script/Niagara.NiagaraComponent'}
    function engine.effects(list)
        local found,seen,assets={},{},{}
        local function note(component)
            local name=text(valid(component:GetClass()):GetFName())
            if not (name:find('Particle',1,true) or name:find('Niagara',1,true)) or seen[component:GetAddress()] then return end
            seen[component:GetAddress()]=true
            -- What Stellar Blade draws: active and visible (an unknown visibility counts as visible).
            local known,visible=pcall(function() return component:IsVisible() end)
            if not component:IsActive() or known and not visible then return end
            -- Niagara components name their system Asset, Cascade ones their Template.
            local ok,asset=pcall(function() return valid(name:find('Niagara',1,true) and component.Asset or component.Template) end)
            local parent=valid(component:GetAttachParent())
            found[#found+1]=string.format('%s[%s]@%s:%s',ok and asset and text(asset:GetFName()) or text(component:GetFName()),name,
                parent and text(parent:GetFName()) or '-',text(component:GetAttachSocketName()))
            if ok and asset then assets[#assets+1]={name=text(asset:GetFName()),object=asset:GetAddress()} end
        end
        local function walk(component,depth)
            each(component.AttachChildren,function(_,child)
                child=valid(child)
                if not child then return end
                note(child)
                if depth<3 then walk(child,depth+1) end
            end)
        end
        for _,entry in ipairs(list or {}) do pcall(walk,entry.handle,1) end
        local pawn=valid(UE.GetPlayer())
        for _,path in ipairs(EFFECT_CLASSES) do
            local class=pawn and valid(StaticFindObject(path))
            if class then each(pawn:K2_GetComponentsByClass(class),function(_,component)
                component=valid(component)
                if component then note(component) end
            end) end
        end
        table.sort(found)
        return found,assets
    end
    -- What the native adapter needs to read attached effects each frame: USceneComponent::AttachChildren,
    -- UNiagaraComponent::Asset and UParticleSystemComponent::Template offsets, UActorComponent::bIsActive's byte and
    -- mask, and both component classes. Found by reflection on the classes, walking up to their supers.
    local function property(path,name)
        local class=valid(StaticFindObject(path))
        while class do
            local found
            class:ForEachProperty(function(p) if text(p:GetFName())==name then found=p end end)
            if found then return found end
            class=valid(class:GetSuperStruct())
        end
        error(path..' has no property '..name)
    end
    function engine.effect_layout()
        local active=property('/Script/Engine.ActorComponent','bIsActive')
        return {children=property('/Script/Engine.SceneComponent','AttachChildren'):GetOffset_Internal(),
            asset=property('/Script/Niagara.NiagaraComponent','Asset'):GetOffset_Internal(),
            template=property('/Script/Engine.ParticleSystemComponent','Template'):GetOffset_Internal(),
            active_byte=active:GetOffset_Internal()+active:GetByteOffset(),active_mask=active:GetByteMask(),
            niagara=valid(StaticFindObject(EFFECT_CLASSES[2])):GetAddress(),particle=valid(StaticFindObject(EFFECT_CLASSES[1])):GetAddress()}
    end
    -- Effect assets loaded right now among the ones Minecraft knows how to draw, as {name=, object=}.
    -- EVE's sword and body effects seen in combat (2026-10-06 effect log), so the first one already streams.
    local FX='/Game/Art/FX/Char/PC/'
    local KNOWN_EFFECTS={}
    for _,path in ipairs({'Skill/Attack/NS_A_ParTrail_SwordDefaultAtk_01','Skill/Attack/NS_A_ParTrail_SwordThrustAtk_01',
        'Skill/Attack/P_A_AnimTrail_SwordLightAtk_01','Skill/Attack/P_A_AnimTrail_SwordStrongAtk_01','Skill/Attack/P_A_AnimTrail_SwordThrust_01',
        'Skill/HoldBurst/NS_A_HoldBurst_Signal','Skill/HoldBurst/NS_A_HoldBurst_SwordFX','WeaponFX/NS_A_Weapon_Signal_Sword',
        'WeaponFX/NS_A_Weapon_Electric_01','WeaponFX/NS_A_WeaponFX_SwordFlame_01','WeaponFX/NS_A_Weapon_FastDraw_01','WeaponFX/NS_A_Weapon_Fold_02',
        'Common/SkillFX/Wind/NS_PC_SpinTrail_01','Common/SkillFX/Wind/NS_MeshWind_02','Common/SkillFX/MeshTrails/NS_A_MeshTrail_03',
        'Common/SkillFX/Kick/NS_PC_KickTrail_02','Skill/ShieldBreakKick/NS_A_KickCharge','Skill/ShieldBreakKick/NS_A_ChargeKickTrail',
        'Skill/ChargeSlash/NS_ChargeSlash_MeshTrail_02','Skill/Evade/NS_A_EvadeBody_01','Skill/Evade/NS_A_EvadeParticle_02',
        'Skill/Parry/NS_JustParry_02'}) do
        local name=path:match('[^/]+$')
        KNOWN_EFFECTS[#KNOWN_EFFECTS+1]=FX..path..'.'..name
    end
    function engine.known_effects()
        local loaded={}
        for _,path in ipairs(KNOWN_EFFECTS) do
            local asset=valid(StaticFindObject(path))
            if asset then loaded[#loaded+1]={name=text(asset:GetFName()),object=asset:GetAddress()} end
        end
        return loaded
    end
    -- Research for EVE's hit reactions and real defensive state: functions and properties named like combat on EVE's
    -- class chain and her components' classes, as 'function Class.Name' or 'property Class.Name'.
    local COMBAT={'Damage','Hit','Evade','Dodge','Parry','Guard','Invinc','Reaction','ActorState','SlowMotion','Dilation','Groggy','Knock'}
    function engine.combat_names()
        local pawn=valid(valid(UE.GetPlayerController()):K2_GetPawn())
        assert(pawn,'The player controller has no pawn.')
        local names,seen={},{}
        local function scan(class)
            while class and not seen[class:GetAddress()] do
                seen[class:GetAddress()]=true
                local owner=text(class:GetFName())
                local function keep(what,member)
                    local name=text(member:GetFName())
                    for _,word in ipairs(COMBAT) do
                        if name:find(word,1,true) then names[#names+1]=what..' '..owner..'.'..name; return end
                    end
                end
                class:ForEachFunction(function(f) keep('function',f) end)
                class:ForEachProperty(function(p) keep('property',p) end)
                class=valid(class:GetSuperStruct())
            end
        end
        scan(valid(pawn:GetClass()))
        each(pawn:K2_GetComponentsByClass(valid(StaticFindObject('/Script/Engine.ActorComponent'))),function(_,component)
            component=valid(component)
            if component then scan(valid(component:GetClass())) end
        end)
        return names
    end
    local function pawn()
        local found=valid(valid(UE.GetPlayerController()):K2_GetPawn())
        assert(found,'The player controller has no pawn.')
        return found
    end
    -- Stellar Blade's global time dilation (UGameplayStatics); returns the previous value. The controller is the world
    -- context, so time can be restored while it has no pawn.
    function engine.time_dilation(value)
        local controller=valid(UE.GetPlayerController())
        assert(controller,'No player controller.')
        local statics=valid(StaticFindObject('/Script/Engine.Default__GameplayStatics'))
        local previous=tonumber(value_of(statics:GetGlobalTimeDilation(controller)))
        assert(previous,'Unreadable time dilation.')
        statics:SetGlobalTimeDilation(controller,value)
        return previous
    end
    -- Plays one of Stellar Blade's Niagara systems on EVE's body mesh (UNiagaraFunctionLibrary::SpawnSystemAttached,
    -- snapped to the mesh, destroyed when done); the system must be loaded or loadable.
    function engine.play_effect(path)
        local system=valid(StaticFindObject(path))
        if not system and type(LoadAsset)=='function' then system=valid(LoadAsset(path)) end
        assert(system,'not loaded')
        local mesh=valid(pawn().Mesh)
        assert(mesh,'EVE has no mesh.')
        local library=valid(StaticFindObject('/Script/Niagara.Default__NiagaraFunctionLibrary'))
        local SNAP_TO_TARGET,NO_POOLING=2,0
        assert(valid(library:SpawnSystemAttached(system,mesh,FName('None'),{X=0,Y=0,Z=0},{Pitch=0,Yaw=0,Roll=0},
            SNAP_TO_TARGET,true,true,NO_POOLING,true)),'nothing spawned')
    end
    -- Stellar Blade's own Shows on EVE. The perfect-dodge Show keeps its Effect keys as in the game (invulnerability while
    -- it plays, Burst gauge, the just-evade counter flag), the just-parry Show its movement block; a Show's SkillResult key
    -- does nothing (its updater's virtuals are the base's no-ops), so no Show gives the Beta of a parry.
    local COMPONENT_SPACE=2 -- ERelativeTransformSpace::RTS_Component
    local function head(mesh) return value_of(value_of(mesh:GetSocketTransform(FName('Bip001-Head'),COMPONENT_SPACE)).Translation) end
    local function loaded(path)
        local object=valid(StaticFindObject(path)) or (type(LoadAsset)=='function' and valid(LoadAsset(path)) or nil)
        return (assert(object,path..' is not loaded')) -- One value: assert also returns its message.
    end
    -- EVE, checked: SBShowActorInterface calls reach their implementation unchecked, and only an SBCharacter has one.
    local function character()
        local eve=pawn()
        assert(eve:IsA(valid(StaticFindObject('/Script/SB.SBCharacter'))),'EVE is not an SBCharacter.')
        return eve
    end
    -- A point 3 m from EVE toward yaw (Unreal degrees), and that direction relative to her facing.
    local function toward(eve,yaw)
        local at,facing=value_of(eve:K2_GetActorLocation()),value_of(eve:K2_GetActorRotation()).Yaw
        local relative=(yaw-facing+180)%360-180 -- Unreal is left-handed: positive is to EVE's right.
        local side=math.abs(relative)<=45 and 'front' or math.abs(relative)>=135 and 'back' or relative>0 and 'right' or 'left'
        local r=math.rad(yaw)
        return {X=at.X+300*math.cos(r),Y=at.Y+300*math.sin(r),Z=at.Z},side,relative
    end
    -- A key of a loaded Show: by its path, else among every loaded key of its class.
    local function show_key(path,name,class)
        loaded(path)
        local key=valid(StaticFindObject(path..':'..name))
        if key then return key end
        local suffix=path:match('[^.]+$')..':'..name
        for _,candidate in ipairs(FindAllOf(class) or {}) do
            candidate=valid(candidate)
            if candidate and candidate:GetFullName():sub(-#suffix)==suffix then return candidate end
        end
        error(name..' not found')
    end
    -- A Show on EVE through PlaySingleShow (built as no hit: keys checking a hit level are dropped). held names a
    -- full-body SBShowAnimKey whose stop-at-move is lifted for this play: the game's skill blocks movement while such a
    -- Show plays, a lone Show does not, so held movement would cut it at once. EVE's animation slot copies the flag when
    -- the key starts, a frame or two later. gate names an SBShowAnimKey whose hit-level check is lifted: the build reads
    -- it inside the call (0x24681bc), so without held it comes back at once. Returns the description and, while a lifted
    -- key is still to be restored, the restore to call (again until it works).
    function engine.play_show(path,yaw,held,gate)
        local eve=character()
        local at,side,relative=toward(eve,yaw or value_of(eve:K2_GetActorRotation()).Yaw)
        loaded(path)
        local keys={}
        if held then keys[#keys+1]={show_key(path,held,'SBShowAnimKey'),'bStopAtMove'} end
        if gate then keys[#keys+1]={show_key(path,gate,'SBShowAnimKey'),'bCheckHitLevel'} end
        for _,key in ipairs(keys) do key[1][key[2]]=false end
        local function restore() for _,key in ipairs(keys) do if valid(key[1]) then key[1][key[2]]=true end end end -- The assets' values.
        local ok,why=pcall(function() eve:PlaySingleShow(path,true,at) end)
        if not ok then restore(); error(why,0) end
        if not held and pcall(restore) then keys={} end
        return string.format('%s toward the %s (%.0f deg)%s%s',path:match('[^.]+$'),side,relative,held and ', stop-at-move lifted' or '',
            gate and ', hit-level check lifted' or ''),#keys>0 and restore or nil
    end
    -- The Niagara and Cascade effects active on EVE's body mesh, as labels.
    function engine.eve_effects() return (engine.effects({{handle=assert(valid(pawn().Mesh),'EVE has no mesh.')}})) end
    -- EVE's hit reaction toward an attack from yaw, by one of three variants of a stock hit Show, which a plain
    -- PlaySingleShow cannot animate (its full-body keys check a hit level, its additive key the AdditivePlay tag).
    -- Both Shows have the same keys: the additive SBShowAnimKey_0, the full-body SBShowAnimKey_2 within front degrees
    -- of EVE's facing and SBShowAnimKey_1 beyond (the other two only play on a TachyNPC).
    -- Returns a description and a sample for hit_sample and hit_restore.
    local HIT='/Game/Art/Show/Hit/'
    local LIGHT_HIT={path=HIT..'Result_Hit_Stand_LightAttack.Result_Hit_Stand_LightAttack',front=45}
    local STRONG_HIT={path=HIT..'Result_Hit_Stand_StrongAttack.Result_Hit_Stand_StrongAttack',front=50}
    local REACTIONS={}
    -- The library builds the Show as a real hit on the player character: a full-body hit, from the front only.
    function REACTIONS.library(show)
        valid(StaticFindObject('/Script/SB.Default__SBBlueprintLibrary')):PlaySBShowToPlayerCharacter(loaded(show.path))
        return 'library'
    end
    -- Lift the hit-level check of the key Stellar Blade would pick for this angle, in memory, until hit_restore.
    function REACTIONS.gate(show,eve,at,relative)
        local key=show_key(show.path,math.abs(relative)<=show.front and 'SBShowAnimKey_2' or 'SBShowAnimKey_1','SBShowAnimKey')
        key.bCheckHitLevel=false
        local function restore() if valid(key) then key.bCheckHitLevel=true end end -- The asset's value.
        local ok,why=pcall(function() eve:PlaySingleShow(show.path,true,at) end)
        if not ok then restore(); error(why,0) end
        return 'gate '..key:GetFName():ToString(),restore
    end
    -- The additive flinch needs AdditivePlay in EVE's transient ShowKeyTagMap: counted as the game counts it, so a real
    -- skill's tag survives the restore.
    function REACTIONS.tag(show,eve,at)
        loaded(show.path)
        local tag=FName('AdditivePlay')
        local function count(tags) return tags:Contains(tag) and tonumber(value_of(tags:Find(tag))) or 0 end
        local tags=value_of(eve.ShowKeyTagMap)
        local before=count(tags)
        tags:Add(tag,before+1)
        local function restore()
            if not valid(eve) then return end
            local now=value_of(eve.ShowKeyTagMap)
            local n=count(now)
            if n<=1 then now:Remove(tag) else now:Add(tag,n-1) end
        end
        local ok,why=pcall(function() eve:PlaySingleShow(show.path,true,at) end)
        if not ok then restore(); error(why,0) end
        return string.format('tag AdditivePlay %d+1',before),restore
    end
    local function alpha(eve,read)
        local ok,value=pcall(read,eve)
        return ok and tonumber(value_of(value)) or nil
    end
    local function custom(eve) return alpha(eve,function(e) return e:GetCurrentCustomAnimAlpha(0) end) end
    local function additive(eve) return alpha(eve,function(e) return e:GetCurrentCustomAnimAddtiveAlpha() end) end -- The game's spelling.
    -- strong: the strong-hit Show, or the light one when that cannot be loaded.
    function engine.hit_reaction(yaw,method,strong)
        local react=assert(REACTIONS[method],'unknown reaction '..tostring(method))
        local eve=character()
        local mesh=assert(valid(eve.Mesh),'EVE has no mesh.')
        local at,side,relative=toward(eve,yaw)
        local show,fallback=LIGHT_HIT,''
        if strong then
            local ok,why=pcall(loaded,STRONG_HIT.path)
            if ok then show=STRONG_HIT else fallback=' (strong Show unavailable: '..tostring(why)..')' end
        end
        -- Busy: EVE already plays a Show (an attack, a guard, a dodge), so the alphas cannot tell this reaction apart.
        local sample={eve=eve,mesh=mesh,head=head(mesh),busy=(custom(eve) or 0)>0.01 or (additive(eve) or 0)>0.01}
        local info
        info,sample.restore=react(show,eve,at,relative)
        return string.format('%s, attack from the %s (%.0f deg), %s%s',info,side,relative,show.path:match('[^.]+$'),fallback),sample
    end
    -- Head travel in mesh space, EVE's custom-animation alphas (her own getters) and her horizontal speed: returns a line,
    -- the full-body and additive alphas, which tell whether a reaction played, and the speed (moving cancels one).
    function engine.hit_sample(sample)
        if not valid(sample.mesh) then return 'mesh gone' end
        local now,before=head(sample.mesh),sample.head
        local eve=valid(sample.eve)
        local full,add=eve and custom(eve),eve and additive(eve)
        local speed=eve and alpha(eve,function(e) local v=value_of(e:GetVelocity()); return math.sqrt(v.X*v.X+v.Y*v.Y) end)
        return string.format('head moved %.1f cm; custom alpha %s; additive alpha %s; speed %s cm/s',
            math.sqrt((now.X-before.X)^2+(now.Y-before.Y)^2+(now.Z-before.Z)^2),
            full and string.format('%.2f',full) or 'n/a',add and string.format('%.2f',add) or 'n/a',
            speed and string.format('%.0f',speed) or 'n/a'),full,add,speed
    end
    function engine.hit_restore(sample) if sample.restore then sample.restore() end end
    -- EVE's full-body animation alpha and what lets a stop-at-move animation stop (her reflected movement state).
    function engine.anim_state()
        local eve=character()
        local function get(read) local ok,v=pcall(read); return ok and tostring(value_of(v)) or 'n/a' end
        local a=custom(eve)
        return string.format('custom alpha %s; move block %s; move type %s; input %s/%s',a and string.format('%.2f',a) or 'n/a',
            get(function() return eve.MoveInputBlockCount end),get(function() return eve.LastMoveType end),
            get(function() return eve.LastMoveFowardInput end),get(function() return eve.LastMoveRightInput end))
    end
    -- Minecraft's lock-on: a hidden, inert bare SBCharacter EVE's own lock-on aims at (ASBCharacter.bLockOn and
    -- LockOnCharacter, which IsLockOn, her rotation and her AnimBP read). No game character is of the native class itself
    -- (all are Blueprint subclasses), so a hidden one found later is a leftover of ours.
    local PROXY_TRIBE={PC='SupplyNPC',TribeNoCheck='TribeNoCheck'} -- CharacterTribeTable: an enemy tribe of EVE's row.
    local function sbcharacter() return (assert(valid(StaticFindObject('/Script/SB.SBCharacter')),'SBCharacter class unavailable')) end
    local function aimed(eve,proxy)
        local target=valid(eve.LockOnCharacter)
        return target~=nil and proxy~=nil and target:GetAddress()==proxy:GetAddress()
    end
    function engine.lock_spawn(at)
        local eve=character()
        local tribe=assert(PROXY_TRIBE[text(eve.Tribe)],'no enemy tribe known for EVE tribe '..text(eve.Tribe))
        -- 100 m above the target, out of everything's way; follow teleports it each frame.
        local proxy=valid(eve:GetWorld():SpawnActor(sbcharacter(),{X=at.X,Y=at.Y,Z=at.Z+10000},{Pitch=0,Yaw=0,Roll=0}))
        assert(proxy,'proxy not spawned')
        proxy:SetActorTickEnabled(false) -- EVE's lock-on reads its root, tribe and flags, never its tick.
        local move=valid(proxy.CharacterMovement)
        if move then move:SetComponentTickEnabled(false) end
        proxy:SetActorEnableCollision(false,false)
        proxy:SetActorHiddenInGame(true)
        proxy.Tribe=FName(tribe)
        proxy.OverrideLockOnMaxDistance=1e5 -- cm: Minecraft owns the range (it releases beyond 28 blocks).
        proxy.OverrideLockOnReleaseDistance=1e5
        local controller=valid(proxy.Controller)
        if controller then proxy:DetachFromControllerPendingDestroy() end
        return proxy,string.format('proxy %s, tribe %s for EVE tribe %s, controller %s',proxy:GetFullName(),
            text(proxy.Tribe),text(eve.Tribe),controller and controller:GetFullName() or 'none')
    end
    function engine.lock_camera(value)
        local eve=character()
        local previous=eve.bLockOnCameraEnable
        eve.bLockOnCameraEnable=value
        return previous
    end
    -- ASBCharacter's lock-on setter by its reflected fields, without its network packet or camera blend. Refused while
    -- Stellar Blade disables lock-on or holds its own.
    function engine.lock_engage(proxy,at)
        local eve=character()
        proxy=assert(valid(proxy),'proxy gone')
        if eve.bDisableLockonTarget or eve.bDisableLockOnControl or eve:IsLockOn() and not aimed(eve,proxy) then return false end
        proxy:K2_SetActorLocation(at,false,{},true)
        proxy.UnavailableLockOn=0
        eve.BackupTargetRelativeCameraBoomHeight=eve.TargetRelativeCameraBoomHeight -- Restored by Stellar Blade's release.
        eve.BackupTargetRelativeCameraBoomLeftRight=eve.TargetRelativeCameraBoomLeftRight
        eve.BackupTargetCamArmLength=eve.TargetCameraBoomLength
        eve.RemainTurnMoveTime=0; eve.TurnMoveVelocity=0; eve.RemainTurnDuration=0; eve.RemainChangeIdleMoveTime=0
        eve.HideTimeForLockOnCharacter=0
        local here=value_of(eve:K2_GetActorLocation())
        eve.CurrentLockOnDistance=math.sqrt((at.X-here.X)^2+(at.Y-here.Y)^2)
        eve.LockOnCharacter=proxy
        eve.bLockOn=true
        return eve:IsLockOn()
    end
    function engine.lock_follow(proxy,at)
        local eve=character()
        proxy=assert(valid(proxy),'proxy gone')
        proxy:K2_SetActorLocation(at,false,{},true)
        eve.HideTimeForLockOnCharacter=0 -- Stellar Blade's off-screen release timer: Minecraft decides what is seen.
        return eve:IsLockOn() and aimed(eve,proxy)
    end
    -- Stellar Blade's own release first (an unavailable target makes IsLockOn release: lock fields cleared, camera boom
    -- restored from the backups); cleared directly if its bDisableLockOnControl blocks that; then camera flag and proxy.
    function engine.lock_release(proxy,camera)
        proxy=valid(proxy)
        local eve,how=valid(UE.GetPlayer()),'EVE gone'
        if eve and eve:IsA(sbcharacter()) then
            how='not locked on the proxy'
            if aimed(eve,proxy) then
                proxy.UnavailableLockOn=1
                eve:IsLockOn()
                how='Stellar Blade release'
                if aimed(eve,proxy) then
                    eve.bLockOn=false
                    pcall(function() eve.LockOnCharacter=nil end) -- bLockOn false already ends IsLockOn.
                    eve.CurrentLockOnDistance=0
                    how='direct release'
                end
            end
            if camera~=nil then eve.bLockOnCameraEnable=camera end
            how=string.format('%s; bLockOn %s, target %s, lock camera %s',how,tostring(eve.bLockOn),
                valid(eve.LockOnCharacter) and 'set' or 'none',tostring(eve.bLockOnCameraEnable))
        end
        if proxy then proxy:K2_DestroyActor() end
        return how
    end
    function engine.lock_sweep() -- Hidden bare SBCharacters: proxies a Lua reload left behind.
        local count=0
        for _,o in ipairs(FindAllOf('SBCharacter') or {}) do
            o=valid(o)
            if o and text(valid(o:GetClass()):GetFName())=='SBCharacter' and o.bHidden then
                engine.lock_release(o,true) -- ponytail: true is DefaultGame.ini's value; read the CDO if a mod changes it.
                count=count+1
            end
        end
        return count
    end
    -- EVE's sword while Minecraft locks: Stellar Blade's lock-on enters battle, which plays her character draw Show and
    -- the sword's unfold Show (attach to the hand at 0.337 s, blade Opacity 0 to 1, FX, sound); ours plays the same pair,
    -- and the sword's fold Show with her sheathe Show on release. Left to Stellar Blade while it is in battle (the native
    -- adapter keeps an attack's battle mode while Minecraft locks) or forces the sword folded or hidden. instant: the draw
    -- Shows' own attach event, sent at once (the blade still unfolds through the Show). Her draw and sheathe Shows are
    -- the same in every EVE row of CharacterAppearanceTable (Player, Player_NoHead); the sword's are the UnFoldShow and
    -- FoldShow of the EquipmentTable row her EquipmentDataList names at EquipmentType_Weapon (the list follows
    -- ESBEquipmentType, not her mesh slots). While her hairstyle hides the ponytail (bHidePonyTail), Stellar Blade plays
    -- the LightSword1 pair's Weapon/Eve/NonPonyTail/<name>_NonTail copies (0x1a2d6a0, called at 0x1a7f358 in the draw
    -- transit, gated by 0x1a738d0, whose result 0x1a72070 copies into bHidePonyTail through 0x1a73770 -> 0x20374e0); the
    -- Fusion pair has none. Another
    -- row, or a chosen Show that fails to play, falls back to the LightSword1 pair (STOCK).
    local BASIC,WEAPON='/Game/Art/Show/CH_P_EVE_01/Basic/','/Game/Art/Show/Weapon/Eve/'
    local DRAW=BASIC..'Eve_Drawing_Moving_LockOn.Eve_Drawing_Moving_LockOn'
    local SHEATHE=BASIC..'Eve_Swording_Moving.Eve_Swording_Moving'
    local WEAPON_SLOT=5 -- ESBSkelMeshSlot::ESBMesh_Weapon1
    local WEAPON_ROW=2 -- EquipmentDataList's EquipmentType_Weapon (1), as a Lua index.
    local STOCK={unfold='Eve_UnFoldSword',fold='Eve_FoldSword'} -- LightSword1's row.
    local SWORD_SHOWS={LightSword1=STOCK,Eve_Fusion_Sword={unfold='Eve_UnFoldSword_Fusion',fold='Eve_FoldSword_Fusion'}}
    local function weapon_show(name,tail)
        return tail and WEAPON..'NonPonyTail/'..name..'_NonTail.'..name..'_NonTail' or WEAPON..name..'.'..name
    end
    -- Plays the sword's Show (which: 'unfold' or 'fold') for EVE's weapon and hairstyle; returns what played and why.
    local function play_sword(eve,which)
        local read,row=pcall(function() return text(value_of(eve.EquipmentDataList)[WEAPON_ROW]) end)
        local seen,hidden=pcall(function() return value_of(eve.bHidePonyTail) end)
        local weapon=read and row or 'unread'
        local ponytail=not seen and 'unread' or hidden==true and 'hidden' or 'shown'
        local shows=SWORD_SHOWS[weapon]
        local path=weapon_show((shows or STOCK)[which],ponytail=='hidden' and (shows or STOCK)==STOCK)
        local why=(shows and weapon or 'weapon '..weapon..' unknown, stock pair')..', ponytail '..ponytail
        local ok,err=pcall(engine.play_show,path)
        if ok then return string.format('%s [%s]',path:match('[^.]+$'),why) end
        local stock=weapon_show(STOCK[which],false)
        if path==stock then error(err,0) end
        engine.play_show(stock)
        return string.format('%s [%s; %s failed: %s]',stock:match('[^.]+$'),why,path:match('[^.]+$'),tostring(err))
    end
    local function sword(eve) -- The sword AnimBP's state (Draw or CriticalDraw in hand, Sword in the hair) and socket.
        local w=valid(eve:GetSBSkeletalMeshComponent(WEAPON_SLOT))
        if not w then return 'none','-' end
        return text(w:GetCustomAnimStateName()),text(w:GetAttachSocketName())
    end
    local function stellar_owns(eve) return eve:IsBattle() or eve.bForceFoldWeapon or eve.bForceFoldWeaponStance or eve.bForceWeaponHide end
    function engine.lock_draw(instant)
        local eve=character()
        local state,socket=sword(eve)
        if state=='Draw' or state=='CriticalDraw' or stellar_owns(eve) then return nil end
        if instant then eve:NotifyShowEvent(FName('CriticalDraw'),FName('CriticalDraw')) else engine.play_show(DRAW) end
        local unfold=play_sword(eve,'unfold') -- After the attach, which then happens even if this Show fails.
        return string.format('%s with %s (sword was %s@%s)',instant and 'instant draw' or 'drawing',unfold,state,socket)
    end
    function engine.lock_sheathe()
        local eve=valid(UE.GetPlayer())
        if not eve or not eve:IsA(sbcharacter()) then return 'EVE gone' end
        local state,socket=sword(eve)
        if stellar_owns(eve) then return string.format('left to Stellar Blade (sword %s@%s)',state,socket) end
        if state~='Draw' and state~='CriticalDraw' then return string.format('already sheathed (sword %s@%s)',state,socket) end
        local fold=play_sword(eve,'fold')
        engine.play_show(SHEATHE)
        return string.format('sheathing with %s (sword was %s@%s)',fold,state,socket)
    end
    -- What Stellar Blade checks each frame and what its lock gives EVE, as one line.
    function engine.lock_status(proxy)
        local eve=character()
        local function get(read) local ok,v=pcall(read); if ok then return v end end -- Keeps false (battle false).
        local p,r=value_of(eve:K2_GetActorLocation()),value_of(eve:K2_GetActorRotation())
        proxy=valid(proxy)
        local t=proxy and value_of(proxy:K2_GetActorLocation())
        local bearing=t and math.deg(math.atan(t.Y-p.Y,t.X-p.X))
        local function off(yaw) return yaw and bearing and string.format('%.0f',(yaw-bearing+540)%360-180) or 'n/a' end
        local camera=get(function() return value_of(valid(UE.GetPlayerController()).PlayerCameraManager:GetCameraRotation()).Yaw end)
        local v,yaw=value_of(eve:GetVelocity()),math.rad(r.Yaw)
        local dolly=get(function() return valid(eve.CameraBoom).CameraDollyAlpha end) -- 1 frames her from her hips, looking up.
        return string.format('IsLockOn %s, anim IsLockOn %s; facing %s, camera %s deg off target; dist %s cm; vel fwd %.0f right %.0f; '..
            'hide %.2f; mode %s freecam %s flying %s; target unavailable %s; disable control %s target %s; dolly %s; battle %s sword %s',
            tostring(eve:IsLockOn()),tostring(get(function() return valid(eve.Mesh):GetAnimInstance().IsLockOn end)),off(r.Yaw),off(camera),
            t and string.format('%.0f',math.sqrt((t.X-p.X)^2+(t.Y-p.Y)^2)) or 'n/a',
            v.X*math.cos(yaw)+v.Y*math.sin(yaw),-v.X*math.sin(yaw)+v.Y*math.cos(yaw),eve.HideTimeForLockOnCharacter,
            tostring(get(function() return eve.CharacterMovement.MovementMode end)),tostring(eve.bFreeCamMode),tostring(eve.bFlyingAction),
            tostring(proxy and proxy.UnavailableLockOn),tostring(eve.bDisableLockOnControl),tostring(eve.bDisableLockonTarget),
            dolly and string.format('%.2f',dolly) or 'n/a',tostring(get(function() return eve:IsBattle() end)),
            get(function() return string.format('%s@%s',sword(eve)) end) or 'n/a')
    end
    -- Stellar Blade's HUD as its own HUD logic fills it (reflected Blueprint variables the logic writes by name, then fires
    -- their parameterless events): the heal and util item slots (WB_MainHUD_ItemSlot_C WB_MainHUD_HealItem and
    -- WB_MainHUD_UtilItem; WB_MainHUD_Lower1Item for the log), EVE's selected drone bullet (SBCharacter
    -- CurrentBulletItemAlias) with its WB_Tps_Bullet2_Slot_C, and the dash attack's WB_MainHUD_Dash_Slot_C. Kept while
    -- valid. FindAllOf walks every object on the game thread, so while one is missing they are looked for again after
    -- HUD_RETRY seconds, doubling up to HUD_RETRY_MAX (back to HUD_RETRY once a search finds one), and at once when one
    -- found went. Each slot: {thumb=, count=, max=, cooldown=, usable=}; thumb is Image_Item_Thumb's brush texture name;
    -- an unreadable property is nil. search: what a search made by this call cost, {ms=, missing=, retry=}. found_util:
    -- this call's search found the util widget, so its values are no continuation of an earlier one's.
    local HUD_RETRY,HUD_RETRY_MAX=2,30
    local HUD_SLOTS={heal='WB_MainHUD_HealItem',util='WB_MainHUD_UtilItem',lower='WB_MainHUD_Lower1Item'}
    local widgets,bullets,hud_next,hud_wait={},{},-math.huge,HUD_RETRY
    local function get(object,name)
        local ok,value=pcall(function() return value_of(object[name]) end)
        if ok then return value end
    end
    local function name_of(object,name)
        local ok,value=pcall(function() return text(object[name]) end)
        if ok then return value end
    end
    local function missing()
        local list={}
        for _,key in ipairs({'heal','util','lower','dash'}) do if not widgets[key] then list[#list+1]=key end end
        if #bullets==0 then list[#list+1]='bullets' end
        return list
    end
    local function find_hud()
        local went=false
        for key,widget in pairs(widgets) do if not valid(widget) then widgets[key]=nil; went=true end end
        for i=#bullets,1,-1 do if not valid(bullets[i]) then table.remove(bullets,i); went=true end end
        if went then hud_next,hud_wait=-math.huge,HUD_RETRY end
        if #missing()==0 or os.clock()<hud_next then return nil end
        local start,before=os.clock(),#missing()
        if not (widgets.heal and widgets.util and widgets.lower) then -- Each class is walked only while one of its widgets is missing.
            for _,o in ipairs(FindAllOf('WB_MainHUD_ItemSlot_C') or {}) do
                o=valid(o)
                local name=o and text(o:GetFName())
                for key,wanted in pairs(HUD_SLOTS) do if name==wanted and not widgets[key] then widgets[key]=o end end
            end
        end
        if not widgets.dash then for _,o in ipairs(FindAllOf('WB_MainHUD_Dash_Slot_C') or {}) do widgets.dash=widgets.dash or valid(o) end end
        if #bullets==0 then for _,o in ipairs(FindAllOf('WB_Tps_Bullet2_Slot_C') or {}) do bullets[#bullets+1]=valid(o) end end
        local now,left=os.clock(),missing()
        if #left<before then hud_wait=HUD_RETRY end
        hud_next=now+hud_wait
        local retry=#left>0 and hud_wait or nil
        if retry then hud_wait=math.min(hud_wait*2,HUD_RETRY_MAX) end
        return {ms=(now-start)*1000,missing=#left>0 and table.concat(left,', ') or 'none',retry=retry}
    end
    local function slot(widget)
        if not widget then return nil end
        local ok,thumb=pcall(function()
            local texture=valid(value_of(value_of(value_of(widget.Image_Item_Thumb).Brush).ResourceObject))
            return texture and text(texture:GetFName()) or nil
        end)
        return {thumb=ok and thumb or nil,count=get(widget,'ItemCount'),max=get(widget,'RecoveryPotionMaxCount'),
            cooldown=get(widget,'CoolTimePercent'),usable=get(widget,'SlotUseAllow')}
    end
    function engine.hud()
        local util=widgets.util
        local result={search=find_hud(),heal=slot(widgets.heal),util=slot(widgets.util),lower=slot(widgets.lower)}
        result.found_util=widgets.util~=nil and widgets.util~=util
        result.dash=widgets.dash and get(widgets.dash,'CoolTimeProgress')
        result.bullet=name_of(valid(UE.GetPlayer()),'CurrentBulletItemAlias')
        for _,b in ipairs(result.bullet and bullets or {}) do
            if name_of(b,'ItemAlias')==result.bullet then result.ammo={count=get(b,'ItemCount'),cooldown=get(b,'CooltimePercent')}; break end
        end
        return result
    end
    -- Registers fn on the item-consume request (ServerRequest_ItemBucketItemConsume); a probe: it may never fire in single player.
    function engine.watch_consume(fn) RegisterHook('/Script/SB.SBNetworkPlayerController:ServerRequest_ItemBucketItemConsume',fn) end
    -- Bone names in component-space order for components without a leader; morph names in MorphTargets order.
    function engine.names(entry)
        local bones,morphs={},{}
        if not entry.leader then
            for index=0,entry.handle:GetNumBones()-1 do bones[#bones+1]=text(entry.handle:GetBoneName(index)) end
        end
        each(entry.mesh_handle.MorphTargets,function(_,morph) morph=valid(morph); if morph then morphs[#morphs+1]=text(morph:GetFName()) end end)
        return bones,morphs
    end
    return engine
end
Adapter.value_of,Adapter.each=value_of,each
return Adapter
