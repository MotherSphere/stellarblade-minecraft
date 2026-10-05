package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
package.loaded.UEHelpers={}
local loaded,Adapter=pcall(require,'scene_adapter')
assert(loaded,'The procedural scene adapter is missing')
local directory=os.tmpname(); os.remove(directory); assert(os.execute('mkdir '..string.format('%q',directory)))
local SESSION=string.rep('a',32)
local name='mc-scene-'..SESSION..'-'..string.rep('b',64)..'.png'
local png='\137PNG\13\10\26\10\0\0\0\13IHDR'..string.pack('>I4I4',256,256)..'\8\6\0\0\0'..string.rep('\0',20)
local function write(data) local file=assert(io.open(directory..'/'..name,'wb')); assert(file:write(data)); assert(file:close()) end
write(png)
local packet={textures={{index=0,file=name,size=#png,width=256,height=256}},
    faces={{texture=0,center={x=1,y=1,z=2},direction='U'}}}
local calls={imports=0,sections=0,sets=0}
local function valid() return true end
local null={IsValid=function()return false end}
local material={IsValid=valid,IsA=function()return true end}
local texture={IsValid=valid,GetAddress=function()return 101 end,
    Blueprint_GetSizeX=function()return 256 end,Blueprint_GetSizeY=function()return 256 end}
local mid={IsValid=valid,GetAddress=function()return 102 end}
function mid:SetTextureParameterValue(...) assert(select('#',...)==2); local n,t=...; assert(n=='SlateUI' and t==texture); self.texture=t end
function mid:SetVectorParameterValue(...) assert(select('#',...)==2); local n,v=...; self[n]=v end
function mid:K2_GetTextureParameterValue(...) assert(select('#',...)==1 and (...)=='SlateUI'); return self.texture end
local mesh={IsValid=valid,IsA=function(_,path)return path=='/Script/ProceduralMeshComponent.ProceduralMeshComponent' end}
function mesh:SetCollisionEnabled(...) assert(select('#',...)==1 and (...)==0); self.collision=0 end
function mesh:SetGenerateOverlapEvents(...) assert(select('#',...)==1 and (...)==false); self.overlaps=false end
function mesh:SetMobility(...) assert(select('#',...)==1 and (...)==2) end
function mesh:CreateMeshSection(...)
    assert(select('#',...)==8,'Target-game CreateMeshSection requires eight inputs')
    local index,vertices,triangles,normals,uv,colors,tangents,collision=...
    assert(index==0 and #vertices==4 and #triangles==6 and #normals==4 and #uv==4 and #colors==4 and #tangents==4 and collision==false)
    assert(vertices[1].X==50 and vertices[1].Y==150 and vertices[1].Z==100)
    assert(triangles[1]==0 and triangles[6]==3 and normals[1].Z==1 and uv[1].X==0)
    assert(colors[1].A==255 and tangents[1].TangentX.X==1 and tangents[1].bFlipTangentY==false)
    assert(calls.hidden and self.collision==0 and self.overlaps==false and calls.actor_collision==false)
    calls.sections=calls.sections+1
    if calls.fail_section then error('array marshaling failed') end
end
function mesh:CreateDynamicMaterialInstance(...)
    assert(select('#',...)==3); local index,parent,name=...; assert(index==0 and parent==material and type(name)=='string')
    self.material=mid; return mid
end
function mesh:SetMaterial(...) assert(select('#',...)==2); local index,value=...; assert(index==0 and value==mid); self.material=value; calls.sets=calls.sets+1 end
function mesh:GetMaterial(...) assert(select('#',...)==1 and (...)==0); return self.material end
local root={IsValid=valid,SetCollisionEnabled=mesh.SetCollisionEnabled,SetGenerateOverlapEvents=mesh.SetGenerateOverlapEvents,SetMobility=mesh.SetMobility}
local actor={IsValid=valid,StaticMeshComponent=root}
function actor:SetActorHiddenInGame(...) assert(select('#',...)==1); calls.hidden=(...) end
function actor:SetActorEnableCollision(...) assert(select('#',...)==2); local enabled,fast=...; assert(enabled==false and fast==false); calls.actor_collision=false end
function actor:K2_DestroyActor(...) assert(select('#',...)==0); if calls.fail_destroy then error('destroy failed') end; calls.destroyed=true end
local identity={tag='native identity transform'}
local pmc_class={IsValid=valid}; local actor_class={IsValid=valid}
function actor:AddComponentByClass(...)
    assert(select('#',...)==4,'Target-game AddComponentByClass requires four inputs')
    local class,manual,transform,deferred=...
    assert(class==pmc_class and manual==false and transform==identity and deferred==false)
    return mesh
end
local world={IsValid=valid}
function world:SpawnActor(...) assert(select('#',...)==3); local class,location,rotation=...; assert(class==actor_class and location.X==10 and location.Y==20 and location.Z==30 and rotation.Yaw==0); return actor end
local library={IsValid=valid}
function library:ImportFileAsTexture2D(...) assert(select('#',...)==2); local owner,path=...; assert(owner==actor and path==directory..'/'..name); calls.imports=calls.imports+1; return texture end
local math_library={IsValid=valid}
function math_library:MakeTransform(...)
    assert(select('#',...)==3); local location,rotation,scale=...
    assert(location.X==0 and location.Y==0 and location.Z==0 and rotation.Pitch==0 and rotation.Yaw==0 and rotation.Roll==0)
    assert(scale.X==1 and scale.Y==1 and scale.Z==1); return identity
end
StaticFindObject=function(path)
    if path=='/Script/Engine.StaticMeshActor' then return actor_class end
    if path=='/Script/ProceduralMeshComponent.ProceduralMeshComponent' then return pmc_class end
    if path=='/Script/Engine.Default__KismetRenderingLibrary' then return library end
    if path=='/Script/Engine.Default__KismetMathLibrary' then return math_library end
    if path=='/Script/UMG.Default__WidgetComponent' then return {IsValid=valid,OpaqueMaterial=material} end
    return null
end
LoadAsset=function() return null end
FName=function(value)return value end
local engine=Adapter.new()
local handle=engine.spawn({world=world},{X=10,Y=20,Z=30})
engine.configure(handle,packet,directory,100)
assert(calls.hidden and calls.imports==1 and calls.sections==1 and calls.sets==1 and engine.valid(handle))
engine.set_visible(handle,true); assert(not calls.hidden)
calls.fail_destroy=true; assert(not pcall(engine.destroy,handle)); assert(handle.actor)
calls.fail_destroy=false; engine.destroy(handle); assert(calls.destroyed and not handle.actor)
handle=engine.spawn({world=world},{X=10,Y=20,Z=30}); calls.fail_section=true
assert(not pcall(engine.configure,handle,packet,directory,100)); assert(handle.actor and calls.hidden)
calls.fail_section=false; engine.destroy(handle)
handle=engine.spawn({world=world},{X=10,Y=20,Z=30}); write(png..'x'); local imports=calls.imports
assert(not pcall(engine.configure,handle,packet,directory,100)); assert(calls.imports==imports)
engine.destroy(handle); assert(os.remove(directory..'/'..name)); assert(os.execute('rmdir '..string.format('%q',directory)))
print('PASS procedural scene arities, array structure, texture size, hidden setup and cleanup ownership; native marshaling remains untested.')
