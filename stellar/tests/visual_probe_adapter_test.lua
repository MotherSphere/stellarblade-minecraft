-- Exact target-game call contracts; native rendering and Unreal GC are substituted.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
package.loaded.UEHelpers={}
local loaded,Adapter=pcall(require,'visual_probe_adapter')
assert(loaded,'The reflected texture-probe adapter is missing')
local directory=os.tmpname(); os.remove(directory); assert(os.execute('mkdir '..string.format('%q',directory)))
local filename=directory..'/minecraft-stone.png'
local png=('89504e470d0a1a0a0000000d49484452000000100000001008060000001ff3ff610000001949444154789c63f8dfc0f09f12cc306ac0a801a3060c17030080007e1f772b03300000000049454e44ae426082'):gsub('..',function(s)return string.char(tonumber(s,16))end)
local function write(data) local f=assert(io.open(filename,'wb')); assert(f:write(data)); assert(f:close()) end
write(png)
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
local function fixture()
    local f={imports=0,lookups=0,sets=0}
    local function valid() return true end
    local null={IsValid=function()return false end}
    local texture={IsValid=valid,GetAddress=function()return 17 end}
    function texture:Blueprint_GetSizeX(...) assert(select('#',...)==0); return f.wrong_size and 32 or 16 end
    function texture:Blueprint_GetSizeY(...) assert(select('#',...)==0); return 16 end
    local parent={IsValid=valid,GetFullName=function()return 'MaterialInstanceConstant /Engine/TestOpaque' end,
        IsA=function(_,path)return path=='/Script/Engine.MaterialInterface' end}
    local mid={IsValid=valid,GetAddress=function()return 18 end}
    function mid:SetTextureParameterValue(...)
        assert(select('#',...)==2); local name,value=...; assert(name=='SlateUI' and value==texture)
        self.texture=value; f.sets=f.sets+1
    end
    function mid:SetVectorParameterValue(...)
        assert(select('#',...)==2); local name,value=...
        assert(name=='TintColorAndOpacity' or name=='BackColor')
        local expected=name=='TintColorAndOpacity' and 1 or 0
        assert(value.R==expected and value.G==expected and value.B==expected and value.A==expected)
        self[name]=value
    end
    function mid:K2_GetTextureParameterValue(...) assert(select('#',...)==1 and (...)=='SlateUI'); return f.wrong_texture and null or self.texture end
    function mid:K2_GetVectorParameterValue(...) assert(select('#',...)==1); return self[(...)] end
    local component={IsValid=valid}
    function component:SetMobility(...) assert(select('#',...)==1 and (...)==2) end
    function component:SetCollisionEnabled(...) assert(select('#',...)==1 and (...)==0); self.collision=0 end
    function component:SetGenerateOverlapEvents(...) assert(select('#',...)==1 and (...)==false); self.overlaps=false end
    function component:SetStaticMesh(...)
        assert(select('#',...)==1 and (...)==f.cube and self.collision==0 and self.overlaps==false and f.hidden and not f.actor_collision)
        return true
    end
    function component:GetNumMaterials(...) assert(select('#',...)==0); return 1 end
    function component:CreateDynamicMaterialInstance(...)
        assert(select('#',...)==3); local slot,source,name=...; assert(slot==0 and source==parent and type(name)=='string')
        self.material=mid; return mid
    end
    function component:GetMaterial(...) assert(select('#',...)==1 and (...)==0); return self.material end
    local actor={IsValid=valid,StaticMeshComponent=component}
    function actor:SetActorHiddenInGame(...) assert(select('#',...)==1); f.hidden=(...); if not f.hidden then assert(mid.texture==texture) end end
    function actor:SetActorEnableCollision(...) assert(select('#',...)==2); local enabled,fast=...; assert(enabled==false and fast==false); f.actor_collision=false end
    function actor:SetActorScale3D(...) assert(select('#',...)==1); local s=(...); assert(s.X==1 and s.Y==1 and s.Z==1) end
    function actor:K2_DestroyActor(...) assert(select('#',...)==0); if f.destroy_error then error('destroy failed') end; f.destroyed=true end
    local world={IsValid=valid,GetAddress=function()return 12 end}
    function world:SpawnActor(...)
        assert(select('#',...)==3); local class,position,rotation=...
        assert(class==f.class and position.X==260 and position.Y==20 and position.Z==255)
        assert(rotation.Pitch==0 and rotation.Yaw==0 and rotation.Roll==0)
        return actor
    end
    local pawn={IsValid=valid,GetAddress=function()return 11 end,GetWorld=function()return world end,
        K2_GetActorLocation=function()return {X=10,Y=20,Z=300} end,K2_GetActorRotation=function()return {Pitch=0,Yaw=0,Roll=0} end,
        CapsuleComponent={IsValid=valid,GetScaledCapsuleHalfHeight=function()return 95 end}}
    local library={IsValid=valid}
    function library:ImportFileAsTexture2D(...)
        assert(select('#',...)==2); local context,path=...; assert(context==actor and path==filename and component.material==mid)
        f.imports=f.imports+1; return f.import_failure and null or texture
    end
    f.cube={IsValid=valid}; f.class={IsValid=valid}
    f.parent=parent; f.mid=mid; f.texture=texture
    local cdo={IsValid=valid,OpaqueMaterial_OneSided=parent,OpaqueMaterial=parent}
    package.loaded.UEHelpers={GetPlayer=function()return pawn end}
    StaticFindObject=function(path)
        if path=='/Script/Engine.StaticMeshActor' then return f.class end
        if path=='/Engine/BasicShapes/Cube.Cube' then return f.cube end
        if path=='/Script/UMG.Default__WidgetComponent' then return f.no_cdo and null or cdo end
        if path=='/Script/Engine.Default__KismetRenderingLibrary' then return library end
        f.lookups=f.lookups+1; return null
    end
    LoadAsset=function(path)
        assert(path:match('^/Engine/EngineMaterials/Widget3DPassThrough_Opaque'))
        f.lookups=f.lookups+1; return f.no_parent and null or parent
    end
    FName=function(name) return name end
    package.loaded.visual_probe_adapter=nil
    f.engine=require('visual_probe_adapter').new()
    function f.spawn() local snapshot=f.engine.snapshot(); return f.engine.spawn(snapshot,{X=260,Y=20,Z=255}) end
    return f
end
check('target arities attach a texture through a private MID before showing the non-colliding cube',function()
    local f=fixture(); local handle=f.spawn(); assert(f.engine.configure(handle,directory):find('TestOpaque',1,true))
    assert(f.imports==1 and f.lookups==0 and not f.hidden and handle.texture==f.texture and handle.mid==f.mid)
    f.engine.destroy(handle); assert(f.destroyed and not handle.actor and not handle.texture and not handle.mid)
end)
check('opaque material fallback is bounded and missing parents fail before import',function()
    local f=fixture(); f.no_cdo=true; local handle=f.spawn(); assert(f.engine.configure(handle,directory)); assert(f.lookups<=4)
    f.engine.destroy(handle)
    f=fixture(); f.no_cdo=true; f.no_parent=true; handle=f.spawn()
    assert(not pcall(f.engine.configure,handle,directory)); assert(f.imports==0 and f.lookups<=4 and f.hidden)
    f.engine.destroy(handle)
end)
check('missing imports, wrong dimensions and rejected assignments stay hidden for cleanup',function()
    for _,failure in ipairs({'import_failure','wrong_size','wrong_texture'}) do
        local f=fixture(); f[failure]=true; local handle=f.spawn()
        assert(not pcall(f.engine.configure,handle,directory)); assert(f.hidden)
        f.engine.destroy(handle); assert(f.destroyed)
    end
end)
check('the fixed image is bounded and grayscale PNG headers are accepted',function()
    local grayscale=png:sub(1,25)..'\0'..png:sub(27)
    write(grayscale); local f=fixture(); local handle=f.spawn(); assert(f.engine.configure(handle,directory)); f.engine.destroy(handle)
    for _,bad in ipairs({'wrong image',png:sub(1,16)..string.pack('>I4',32)..png:sub(21),png..string.rep('x',65536)}) do
        write(bad); f=fixture(); handle=f.spawn(); assert(not pcall(f.engine.configure,handle,directory)); assert(f.imports==0); f.engine.destroy(handle)
    end
    write(png)
end)
check('failed actor destruction retains every native handle for retry',function()
    local f=fixture(); local handle=f.spawn(); assert(f.engine.configure(handle,directory)); f.destroy_error=true
    assert(not pcall(f.engine.destroy,handle)); assert(handle.actor and handle.mid and handle.texture)
    f.destroy_error=false; f.engine.destroy(handle); assert(not handle.actor and not handle.mid and not handle.texture)
end)
assert(os.remove(filename)); assert(os.execute('rmdir '..string.format('%q',directory)))
print(count..' texture adapter checks passed; material pixels remain unverified.')
