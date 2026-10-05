-- Reflected game API only. Call every method on the game thread.
local UE=require('UEHelpers')
local Adapter={}
local function valid(object) return object and object:IsValid() end
local function vec(value) return {X=value.X,Y=value.Y,Z=value.Z} end
local function rot(value) return {Pitch=value.Pitch,Yaw=value.Yaw,Roll=value.Roll} end
local function library()
    local object=StaticFindObject('/Script/Engine.Default__KismetRenderingLibrary')
    assert(valid(object),'KismetRenderingLibrary is unavailable in this game build.')
    return object
end
local function component(actor)
    assert(valid(actor),'Capture actor is no longer valid.')
    local capture=actor.CaptureComponent2D
    assert(valid(capture),'SceneCapture2D has no valid capture component.')
    return capture
end
function Adapter.new()
    local engine={}
    function engine.camera()
        local pawn=UE.GetPlayer()
        assert(valid(pawn),'Load a playable save before capturing EVE.')
        local world=pawn:GetWorld()
        local controller=UE.GetPlayerController()
        assert(valid(world) and valid(controller),'The playable world or controller is unavailable.')
        local camera=controller.PlayerCameraManager
        local capsule=pawn.CapsuleComponent
        assert(valid(camera) and valid(capsule),'The native camera or EVE capsule is unavailable.')
        return {pawn=pawn,world=world,pawn_id=pawn:GetAddress(),world_id=world:GetAddress(),
            position=vec(camera:GetCameraLocation()),rotation=rot(camera:GetCameraRotation()),fov=camera:GetFOVAngle(),
            pawn_position=vec(pawn:K2_GetActorLocation()),half_height=capsule:GetScaledCapsuleHalfHeight()}
    end
    function engine.spawn(camera)
        local class=StaticFindObject('/Script/Engine.SceneCapture2D')
        assert(valid(class),'SceneCapture2D is unavailable in this game build.')
        local actor=camera.world:SpawnActor(class,camera.position,camera.rotation)
        assert(valid(actor),'SceneCapture2D could not be spawned.')
        return actor
    end
    function engine.configure(actor,camera,mode)
        local capture=component(actor)
        capture.bCaptureEveryFrame=false
        capture.bCaptureOnMovement=false
        capture.PrimitiveRenderMode=2 -- PRM_UseShowOnlyList
        capture.ProjectionType=0 -- Perspective
        capture.CompositeMode=0 -- SCCM_Overwrite
        capture.CaptureSource=mode.source
        capture.FOVAngle=camera.fov
        capture:ClearShowOnlyComponents()
        capture:ShowOnlyActorComponents(camera.pawn,true)
    end
    function engine.create_target(camera,mode)
        -- This Stellar Blade SDK exposes six inputs; newer Unreal versions add a seventh.
        local target=library():CreateRenderTarget2D(camera.pawn,mode.width,mode.height,mode.format,
            {R=0,G=0,B=0,A=1},false)
        assert(valid(target),'Render target could not be created.')
        return target
    end
    function engine.bind(actor,target) component(actor).TextureTarget=target end
    function engine.capture(actor) component(actor):CaptureScene() end
    function engine.export(actor,target,directory,filename)
        library():ExportRenderTarget(actor,target,directory,filename)
    end
    function engine.prepare_async(handle,completed)
        local writer=StaticFindObject('/Script/ImageWriteQueue.Default__ImageWriteBlueprintLibrary')
        assert(valid(writer),'ImageWriteQueue is unavailable in this game build.')
        assert(type(RegisterHook)=='function' and type(UnregisterHook)=='function','Native completion hooks are unavailable.')
        local watch={writer=writer,address=handle.actor:GetAddress(),armed=false}
        handle.watch=watch
        watch.pre,watch.post=RegisterHook('/Script/Engine.Actor:SetActorHiddenInGame',function(context,value)
            if not watch.armed then return end
            local object=context:get()
            if not valid(object) or object:GetAddress()~=watch.address then return end
            local success=value
            if type(value)~='boolean' then success=value:get() end
            if type(success)~='boolean' then return end
            watch.armed=false
            -- This ordinary bool UFunction is the owned actor's completion sink.
            -- Only mark completion here; the controller cleans up on a later tick.
            completed(success)
        end)
    end
    function engine.export_async(handle,path)
        local watch=assert(handle.watch,'Async completion is not prepared.')
        watch.armed=true
        watch.writer:ExportToDisk(handle.target,path,{Format=0,CompressionQuality=0,bOverwriteFile=false,bAsync=true,
            OnComplete={Object=handle.actor,FunctionName=FName('SetActorHiddenInGame')}})
    end
    function engine.unwatch(handle)
        local watch=handle.watch
        if not watch then return end
        watch.armed=false
        if watch.pre then UnregisterHook('/Script/Engine.Actor:SetActorHiddenInGame',watch.pre,watch.post) end
        handle.watch=nil
    end
    function engine.destroy(actor)
        if not valid(actor) then return end
        local capture=actor.CaptureComponent2D
        if valid(capture) then
            capture.bCaptureEveryFrame=false
            capture.bCaptureOnMovement=false
            capture.TextureTarget=CreateInvalidObject()
        end
        actor:K2_DestroyActor()
    end
    function engine.release(target)
        if valid(target) then library():ReleaseRenderTarget2D(target) end
    end
    return engine
end
return Adapter
