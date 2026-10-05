-- Bounded procedural mesh adapter. All UObject calls run on the game thread.
local ProbeAdapter=require('visual_probe_adapter')
local Geometry=require('scene_geometry')
local Adapter={}
local function valid(object) return object and object:IsValid() end
local function same(a,b) return valid(a) and valid(b) and a:GetAddress()==b:GetAddress() end
local function required(path)
    local object=StaticFindObject(path)
    assert(valid(object),'Native scene API is unavailable: '..path)
    return object
end
local function image_path(directory,texture)
    local path=directory..'/'..texture.file
    local file=assert(io.open(path,'rb'),'Native scene texture is unavailable.')
    local data=file:read(1024*1024+1); file:close()
    assert(data and #data==texture.size and #data>=33 and #data<=1024*1024,
        'Native scene texture length differs from its descriptor.')
    assert(data:sub(1,8)=='\137PNG\13\10\26\10' and data:sub(9,16)=='\0\0\0\13IHDR',
        'Native scene texture is not a PNG.')
    local width,height=string.unpack('>I4I4',data,17)
    assert(width==256 and height==256,'Native scene texture must be 256 by 256.')
    return path
end
function Adapter.new(log)
    log=log or function() end
    local probe=ProbeAdapter.new()
    local engine={snapshot=probe.snapshot}
    function engine.spawn(snapshot,anchor)
        local class=required('/Script/Engine.StaticMeshActor')
        local actor=snapshot.world:SpawnActor(class,anchor,{Pitch=0,Yaw=0,Roll=0})
        assert(valid(actor),'Native scene host could not be created.')
        return {actor=actor,layers={}}
    end
    function engine.configure(handle,packet,directory,scale)
        local actor=handle.actor
        actor:SetActorHiddenInGame(true); actor:SetActorEnableCollision(false,false)
        local root=actor.StaticMeshComponent
        assert(valid(root),'Native scene host has no root component.')
        root:SetMobility(2); root:SetCollisionEnabled(0); root:SetGenerateOverlapEvents(false)
        local math_library=required('/Script/Engine.Default__KismetMathLibrary')
        local identity=math_library:MakeTransform({X=0,Y=0,Z=0},{Pitch=0,Yaw=0,Roll=0},{X=1,Y=1,Z=1})
        local class=required('/Script/ProceduralMeshComponent.ProceduralMeshComponent')
        handle.mesh=actor:AddComponentByClass(class,false,identity,false)
        local mesh=handle.mesh
        assert(valid(mesh) and mesh:IsA('/Script/ProceduralMeshComponent.ProceduralMeshComponent'),
            'Native procedural mesh component could not be created.')
        mesh:SetMobility(2); mesh:SetCollisionEnabled(0); mesh:SetGenerateOverlapEvents(false)
        local sections=Geometry.groups(packet,scale)
        if #sections==0 then return end
        local parent=ProbeAdapter.parent_material()
        assert(parent:IsA('/Script/Engine.MaterialInterface'),'Native scene parent is not a material.')
        log('Native scene parent material: '..parent:GetFullName())
        local rendering=required('/Script/Engine.Default__KismetRenderingLibrary')
        for _,section in ipairs(sections) do
            local index=section.texture
            local texture=assert(packet.textures[index+1],'Native scene section has no texture.')
            local path=image_path(directory,texture)
            -- The section creates its material slot; assign the private MID afterward.
            mesh:CreateMeshSection(index,section.vertices,section.triangles,section.normals,
                section.uv0,section.colors,section.tangents,false)
            local layer={}; handle.layers[#handle.layers+1]=layer
            layer.mid=mesh:CreateDynamicMaterialInstance(index,parent,FName('SBMCScene'..index))
            assert(valid(layer.mid),'Native scene material instance could not be created.')
            mesh:SetMaterial(index,layer.mid)
            assert(same(mesh:GetMaterial(index),layer.mid),'Native scene material slot did not retain its instance.')
            layer.texture=rendering:ImportFileAsTexture2D(actor,path)
            assert(valid(layer.texture),'Native scene texture import failed.')
            assert(layer.texture:Blueprint_GetSizeX()==256 and layer.texture:Blueprint_GetSizeY()==256,
                'Imported native scene texture dimensions changed.')
            layer.mid:SetTextureParameterValue(FName('SlateUI'),layer.texture)
            for _,entry in ipairs({{'TintColorAndOpacity',1},{'BackColor',0}}) do
                local name,value=FName(entry[1]),entry[2]
                layer.mid:SetVectorParameterValue(name,{R=value,G=value,B=value,A=value})
                local actual=layer.mid:K2_GetVectorParameterValue(name)
                assert(actual.R==value and actual.G==value and actual.B==value and actual.A==value,
                    'Native scene material tint was not assigned.')
            end
            assert(same(layer.mid:K2_GetTextureParameterValue(FName('SlateUI')),layer.texture),
                'Native scene texture parameter was not assigned.')
        end
    end
    function engine.set_visible(handle,visible)
        assert(valid(handle.actor),'Native scene actor is no longer valid.')
        handle.actor:SetActorHiddenInGame(not visible)
    end
    function engine.valid(handle) return valid(handle.actor) and valid(handle.mesh) end
    function engine.destroy(handle)
        probe.destroy(handle)
        handle.mesh=nil; handle.layers=nil
    end
    return engine
end
return Adapter
