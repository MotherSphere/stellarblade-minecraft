-- Target-game reflected API only. Every method runs on the game thread.
local UE=require('UEHelpers')
local Adapter={}
local function valid(object) return object and object:IsValid() end
local function load(path)
    local object=StaticFindObject(path)
    if not valid(object) then
        local ok,value=pcall(LoadAsset,path)
        if ok then object=value end
    end
    return object
end
local function parent_material()
    local defaults=StaticFindObject('/Script/UMG.Default__WidgetComponent')
    if valid(defaults) then
        for _,field in ipairs({'OpaqueMaterial_OneSided','OpaqueMaterial'}) do
            local ok,parent=pcall(function() return defaults[field] end)
            if ok and valid(parent) then return parent end
        end
    end
    for _,name in ipairs({'Widget3DPassThrough_Opaque_OneSided','Widget3DPassThrough_Opaque'}) do
        local parent=load('/Engine/EngineMaterials/'..name..'.'..name)
        if valid(parent) then return parent end
    end
    error('No supported opaque Widget3D material is available in this game build.')
end
local function image_path(directory)
    local path=directory..'/minecraft-stone.png'
    local file=assert(io.open(path,'rb'),'The private minecraft-stone.png probe image is missing.')
    local data=file:read(65537); file:close()
    assert(data and #data>=33 and #data<=65536 and data:sub(1,8)=='\137PNG\13\10\26\10'
        and data:sub(9,16)=='\0\0\0\13IHDR','The texture probe image must be a bounded PNG.')
    local width,height=string.unpack('>I4I4',data,17)
    assert(width==16 and height==16,'The texture probe requires the 16 by 16 stone image.')
    return path
end
local function same(a,b) return valid(a) and valid(b) and a:GetAddress()==b:GetAddress() end
function Adapter.new()
    local engine={}
    function engine.snapshot()
        local pawn=UE.GetPlayer()
        assert(valid(pawn),'Load a playable save before creating the texture probe.')
        local world,capsule=pawn:GetWorld(),pawn.CapsuleComponent
        assert(valid(world) and valid(capsule),'The playable world or capsule is unavailable.')
        local p,r=pawn:K2_GetActorLocation(),pawn:K2_GetActorRotation()
        return {world=world,pawn_id=pawn:GetAddress(),world_id=world:GetAddress(),
            position={X=p.X,Y=p.Y,Z=p.Z},rotation={Yaw=r.Yaw},half_height=capsule:GetScaledCapsuleHalfHeight()}
    end
    function engine.spawn(origin,center)
        local class=StaticFindObject('/Script/Engine.StaticMeshActor')
        assert(valid(class),'StaticMeshActor is unavailable in this game build.')
        local actor=origin.world:SpawnActor(class,center,{Pitch=0,Yaw=0,Roll=0})
        assert(valid(actor),'The texture probe actor could not be spawned.')
        return {actor=actor}
    end
    function engine.configure(handle,directory)
        local actor=handle.actor
        actor:SetActorHiddenInGame(true)
        actor:SetActorEnableCollision(false,false)
        local mesh=actor.StaticMeshComponent
        assert(valid(mesh),'The texture probe has no mesh component.')
        mesh:SetCollisionEnabled(0); mesh:SetGenerateOverlapEvents(false); mesh:SetMobility(2)
        local path=image_path(directory)
        local cube=load('/Engine/BasicShapes/Cube.Cube')
        assert(valid(cube),'The engine cube mesh is unavailable.')
        local parent=parent_material()
        assert(parent:IsA('/Script/Engine.MaterialInterface'),'The texture parent is not a material.')
        assert(mesh:SetStaticMesh(cube),'The engine cube mesh could not be assigned.')
        actor:SetActorScale3D({X=1,Y=1,Z=1})
        assert(mesh:GetNumMaterials()>0,'The cube has no material slot.')
        handle.mid=mesh:CreateDynamicMaterialInstance(0,parent,FName('SBMCTextureProbe'))
        assert(valid(handle.mid) and same(mesh:GetMaterial(0),handle.mid),'The private material instance was not assigned.')
        local library=StaticFindObject('/Script/Engine.Default__KismetRenderingLibrary')
        assert(valid(library),'KismetRenderingLibrary is unavailable.')
        handle.texture=library:ImportFileAsTexture2D(actor,path)
        assert(valid(handle.texture),'The native image importer rejected the stone texture.')
        assert(handle.texture:Blueprint_GetSizeX()==16 and handle.texture:Blueprint_GetSizeY()==16,
            'The imported texture dimensions differ from the probe image.')
        handle.mid:SetTextureParameterValue(FName('SlateUI'),handle.texture)
        for _,entry in ipairs({{'TintColorAndOpacity',1},{'BackColor',0}}) do
            local name,value=FName(entry[1]),entry[2]
            handle.mid:SetVectorParameterValue(name,{R=value,G=value,B=value,A=value})
            local actual=handle.mid:K2_GetVectorParameterValue(name)
            assert(actual.R==value and actual.G==value and actual.B==value and actual.A==value,'The material tint was not assigned.')
        end
        assert(same(handle.mid:K2_GetTextureParameterValue(FName('SlateUI')),handle.texture),'The material texture was not assigned.')
        actor:SetActorHiddenInGame(false)
        return parent:GetFullName()
    end
    function engine.valid(handle) return valid(handle.actor) end
    function engine.destroy(handle)
        if valid(handle.actor) then handle.actor:K2_DestroyActor() end
        -- The owned component/MID references are released with the actor. Imported
        -- Texture2D and MID objects use normal Unreal GC, not render-target release.
        handle.actor=nil; handle.mid=nil; handle.texture=nil
    end
    return engine
end
return Adapter
