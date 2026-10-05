-- UE4SS value shapes seen in game: converted lists of RemoteUnrealParam, TArray userdata and nested structs.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local function fname(text) return {type=function() return 'FName' end,ToString=function() return text end} end
local function wrap(value) return {type=function() return 'RemoteUnrealParam' end,get=function() return value end} end
local function tarray(items,wrapped)
    return {type=function() return 'TArray' end,ForEach=function(_,callback)
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
print('PASS pose adapter: wrapped component lists, TArray structs, weak leaders, socket fallback and failing components')
