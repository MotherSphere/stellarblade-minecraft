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
    function engine.identities()
        local pawn=valid(UE.GetPlayer())
        assert(pawn,'Load a playable save before streaming EVE.')
        local class=valid(StaticFindObject('/Script/Engine.SkeletalMeshComponent'))
        assert(class,'SkeletalMeshComponent is unavailable in this game build.')
        local list={}
        each(pawn:K2_GetComponentsByClass(class),function(_,component)
            component=valid(component)
            local mesh=component and valid(component.SkeletalMesh)
            if not mesh then return end
            local found,leader=pcall(function() return valid(value_of(component.MasterPoseComponent):Get()) end)
            list[#list+1]={handle=component,name=text(component:GetFName()),object=component:GetAddress(),
                class=valid(component:GetClass()):GetAddress(),mesh=mesh:GetAddress(),mesh_name=text(mesh:GetFName()),
                leader=found and leader and text(leader:GetFName()) or nil,mesh_handle=mesh}
        end)
        return list
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
            if not component:IsActive() then return end
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
    local KNOWN_EFFECTS={'/Game/Art/FX/Char/PC/Skill/Attack/NS_A_ParTrail_SwordDefaultAtk_01.NS_A_ParTrail_SwordDefaultAtk_01',
        '/Game/Art/FX/Char/PC/WeaponFX/NS_A_Weapon_Signal_Sword.NS_A_Weapon_Signal_Sword',
        '/Game/Art/FX/Char/PC/WeaponFX/NS_A_Weapon_Electric_01.NS_A_Weapon_Electric_01',
        '/Game/Art/FX/Char/PC/WeaponFX/NS_A_WeaponFX_SwordFlame_01.NS_A_WeaponFX_SwordFlame_01'}
    function engine.known_effects()
        local loaded={}
        for _,path in ipairs(KNOWN_EFFECTS) do
            local asset=valid(StaticFindObject(path))
            if asset then loaded[#loaded+1]={name=text(asset:GetFName()),object=asset:GetAddress()} end
        end
        return loaded
    end
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
