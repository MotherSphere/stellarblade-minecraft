-- Game-thread UE4SS access for the pose probe; returns plain Lua numbers and strings.
local UE=require('UEHelpers')
local Adapter={}
local PARENT_BONE_SPACE=3 -- ERelativeTransformSpace::RTS_ParentBoneSpace
local function valid(object) return object and object:IsValid() end
local function each(array,callback)
    -- UE4SS may return converted Lua tables or TArray userdata.
    if type(array)=='table' then for index,value in ipairs(array) do callback(index,value) end return end
    array:ForEach(function(index,element) callback(index,element:get()) end)
end
local function text(name) return type(name)=='string' and name or name:ToString() end
local function transform(t)
    local r,p,s=t.Rotation,t.Translation,t.Scale3D
    return {qx=r.X,qy=r.Y,qz=r.Z,qw=r.W,x=p.X,y=p.Y,z=p.Z,sx=s.X,sy=s.Y,sz=s.Z}
end
function Adapter.new()
    local engine,fallback={},{}
    function engine.components()
        local pawn=UE.GetPlayer()
        assert(valid(pawn),'Load a playable save before probing EVE.')
        local class=StaticFindObject('/Script/Engine.SkeletalMeshComponent')
        assert(valid(class),'SkeletalMeshComponent is unavailable in this game build.')
        local list={}
        each(pawn:K2_GetComponentsByClass(class),function(_,component)
            if not valid(component) then return end
            local mesh,leader,parent=component.SkeletalMesh,component.MasterPoseComponent:Get(),component:GetAttachParent()
            local morphs={}
            if valid(mesh) then each(mesh.MorphTargets,function(_,morph) if valid(morph) then morphs[#morphs+1]=morph:GetFName():ToString() end end) end
            list[#list+1]={handle=component,name=component:GetFName():ToString(),
                mesh=valid(mesh) and mesh:GetFName():ToString() or 'none',bones=component:GetNumBones(),
                leader=valid(leader) and leader:GetFName():ToString() or nil,
                parent=valid(parent) and parent:GetFName():ToString() or nil,
                socket=text(component:GetAttachSocketName()),morphs=morphs}
        end)
        return list
    end
    local function sockets(component)
        -- Fallback: one reflected call per bone and per morph.
        local result={names={},transforms={},weights={},method='sockets'}
        for index=0,component:GetNumBones()-1 do
            local name=component:GetBoneName(index)
            result.names[index+1]=text(name)
            result.transforms[index+1]=transform(component:GetSocketTransform(name,PARENT_BONE_SPACE))
        end
        local mesh=component.SkeletalMesh
        if valid(mesh) then
            each(mesh.MorphTargets,function(_,morph)
                if valid(morph) then result.weights[#result.weights+1]=component:GetMorphTarget(morph:GetFName()) end
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
            component:SnapshotPose(snapshot)
            assert(snapshot.bIsValid,'SnapshotPose returned an invalid pose.')
            local value={names={},transforms={},weights={},method='snapshot'}
            each(snapshot.BoneNames,function(index,name) value.names[index]=text(name) end)
            each(snapshot.LocalTransforms,function(index,t) value.transforms[index]=transform(t) end)
            each(snapshot.MorphTargetWeights,function(index,weight) value.weights[index]=weight end)
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
    return engine
end
return Adapter
