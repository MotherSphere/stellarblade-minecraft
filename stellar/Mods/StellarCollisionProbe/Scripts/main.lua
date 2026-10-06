local UE = require('UEHelpers')
local Probe = require('probe')
local engine = {}
local function log(message) print('[StellarCollisionProbe] '..tostring(message)..'\n') end
local function valid(object) return object and object:IsValid() end
local function vec(value) return {X=value.X,Y=value.Y,Z=value.Z} end
local function rot(value) return {Pitch=value.Pitch,Yaw=value.Yaw,Roll=value.Roll} end
local function player()
    local pawn = UE.GetPlayer()
    assert(valid(pawn), 'Load a playable save before using the probe.')
    local world = pawn:GetWorld()
    assert(valid(world), 'The player has no valid world.')
    return pawn, world
end
local function native_frame_count()
    local system=StaticFindObject('/Script/Engine.Default__KismetSystemLibrary')
    assert(valid(system),'native frame counter library is unavailable')
    local frames=system:GetFrameCount()
    assert(type(frames)=='number' and frames>=0 and frames<=9007199254740991 and frames%1==0,
        'native frame counter is unavailable')
    return frames,system
end
local function real_seconds(world)
    local gameplay=StaticFindObject('/Script/Engine.Default__GameplayStatics')
    assert(valid(gameplay),'native timing library is unavailable')
    local seconds=gameplay:GetRealTimeSeconds(world)
    assert(type(seconds)=='number' and seconds>=0 and seconds<1e15,'native timing counter is unavailable')
    return seconds
end
local function native_timing(world)
    local frames,system=native_frame_count()
    local seconds=real_seconds(world)
    return string.format('native_frame_count=%d native_real_seconds=%.6f idle_when_background=%d max_fps=%.3f',
        frames,seconds,system:GetConsoleVariableIntValue('t.IdleWhenNotForeground'),
        system:GetConsoleVariableFloatValue('t.MaxFPS'))
end
local function raw_trace(message)
    if message:find('stage=begin',1,true) then
        local ok,timing=pcall(function()local _,world=player();return native_timing(world)end)
        message=message..' '..(ok and timing or 'native timing unavailable: '..tostring(timing))
    end
    log(message)
end
function engine.snapshot()
    local pawn,world = player()
    local capsule = pawn.CapsuleComponent
    assert(valid(capsule), 'The controlled pawn has no capsule; no scene changes made.')
    return {pawn_id=pawn:GetAddress(),world_id=world:GetAddress(),
        position=vec(pawn:K2_GetActorLocation()), rotation=rot(pawn:K2_GetActorRotation()),
        half_height=capsule:GetScaledCapsuleHalfHeight(),radius=capsule:GetScaledCapsuleRadius()}
end
function engine.control_context()
    local pawn,world=player()
    local controller=UE.GetPlayerController()
    if not valid(controller) or not controller:IsLocalPlayerController() then return nil end
    local controlled=controller:K2_GetPawn()
    local controller_world=controller:GetWorld()
    if not valid(controlled) or controlled:GetAddress()~=pawn:GetAddress()
        or not valid(controller_world) or controller_world:GetAddress()~=world:GetAddress() then return nil end
    local gameplay=StaticFindObject('/Script/Engine.Default__GameplayStatics')
    assert(valid(gameplay),'Native pause state is unavailable.')
    if gameplay:IsGamePaused(world) or pawn:IsAttachedToLevelSequenceWithoutPlayable() then return nil end
    return {pawn=pawn:GetAddress(),world=world:GetAddress()}
end
function engine.valid(handle) return valid(handle.actor) end

-- Visuals are optional. Collision always belongs to the TriggerBox, never this mesh.
local function add_visual(world, spec, handle)
    local mesh = StaticFindObject('/Engine/BasicShapes/Cube.Cube')
    if not valid(mesh) then mesh = LoadAsset('/Engine/BasicShapes/Cube.Cube') end
    if not valid(mesh) then return end
    local class = StaticFindObject('/Script/Engine.StaticMeshActor')
    if not valid(class) then return end
    local visual = world:SpawnActor(class,spec.center,{Pitch=0,Yaw=0,Roll=0})
    if not valid(visual) then return end
    -- Keep ownership even if optional setup fails.
    handle.visual = visual
    local component = visual.StaticMeshComponent
    assert(valid(component), 'Optional visual has no StaticMeshComponent.')
    component:SetMobility(2)
    component:SetCollisionEnabled(0)
    visual:SetActorEnableCollision(false,false)
    assert(component:SetStaticMesh(mesh), 'Optional cube visual could not be set.')
    visual:SetActorScale3D({X=spec.extent.X/50,Y=spec.extent.Y/50,Z=spec.extent.Z/50})
    visual:SetActorHiddenInGame(spec.visuals_visible==false)
end
function engine.spawn(spec)
    local _,world = player()
    local class = StaticFindObject('/Script/Engine.TriggerBox')
    assert(valid(class), 'TriggerBox class unavailable in this game build.')
    local actor = world:SpawnActor(class,spec.center,{Pitch=0,Yaw=0,Roll=0})
    assert(valid(actor), 'TriggerBox could not be spawned.')
    -- Register ownership in the lifecycle before any fallible component setup.
    return {actor=actor}
end
function engine.configure(handle, spec)
    local actor = handle.actor
    local box = actor.CollisionComponent
    assert(valid(box) and box:IsA('/Script/Engine.BoxComponent'), 'TriggerBox has no BoxComponent.')
    box:SetMobility(2)
    box:SetGenerateOverlapEvents(false)
    box:SetBoxExtent(spec.extent,true)
    box:SetCollisionObjectType(1) -- WorldDynamic; bilateral response must be tested in game.
    box:SetCollisionResponseToAllChannels(2) -- Block
    box:SetCollisionEnabled(3) -- QueryAndPhysics
    -- Stellar Blade adds inFastUpdate; use the non-fast path for this experiment.
    actor:SetActorEnableCollision(true,false)
    assert(box:GetCollisionEnabled()==3, 'Collision mode was not accepted.')
    local shown, why = pcall(add_visual,actor:GetWorld(),spec,handle)
    if not shown then log('Optional cube visual unavailable: '..tostring(why)) end
    log(string.format('Spawned %s at %.1f %.1f %.1f, half-extents %.1f %.1f %.1f',
        spec.role,spec.center.X,spec.center.Y,spec.center.Z,spec.extent.X,spec.extent.Y,spec.extent.Z))
end
function engine.destroy(handle)
    if valid(handle.visual) then handle.visual:K2_DestroyActor() end
    if valid(handle.actor) then handle.actor:K2_DestroyActor() end
end
function engine.set_visual_visible(handle,visible)
    if valid(handle.visual) then handle.visual:SetActorHiddenInGame(not visible) end
end
function engine.move(position, rotation)
    local pawn = player()
    local hit = {}
    local moved = pawn:K2_SetActorLocationAndRotation(position,rotation,false,hit,true)
    if not moved then return false end
    local actual = pawn:K2_GetActorLocation()
    return math.abs(actual.X-position.X)<1 and math.abs(actual.Y-position.Y)<1 and math.abs(actual.Z-position.Z)<1
end

local probe = Probe.new(engine)
local active = probe
local bridge
local capture
local stream
local hd_session
local control_permit
local visual
local scene
local raw
local pose_recorder
local pose_stream
local last_hd_frame
local configured,config = pcall(require,'bridge_config')
if configured then
    local Terrain = require('terrain')
    bridge = require('bridge').new(Terrain.new(engine,config.scale),config.directory,log)
    local capture_engine=require('capture_adapter').new()
    capture = require('capture').new(capture_engine,config.directory,log)
    visual = require('visual_probe').new(require('visual_probe_adapter').new(),config.directory)
    scene = require('native_scene').new(require('scene_adapter').new(log),bridge,config.directory,config.scale)
    stream = require('capture_stream').new(capture,function()
        return active==bridge and bridge.pose() or nil
    end,config.scale)
    raw=require('raw_capture').new(capture_engine,config.directory,nil,raw_trace,function() return last_hd_frame end)
    hd_session=require('capture_session').new(capture,function()
        return active==bridge and bridge.pose() or nil
    end,config.directory,config.scale,function()
        return not stream.busy() and not scene.enabled() and scene.status()=='idle' and visual.status()=='idle'
    end,nil,raw)
    control_permit=require('control_permit').new(config.directory)
    pose_recorder=require('pose_probe').new(require('pose_adapter').new(),config.directory,os.clock,log)
    -- Minecraft's avatar view requests the stream; the entered link fixes the Unreal-to-Minecraft placement.
    pose_stream=require('pose_stream').new(require('pose_adapter').new(),config.directory,os.time,log,function()
        local pose=active==bridge and bridge.pose() or nil
        return pose and pose.state=='entered' and pose.anchor and {session=pose.session,anchor=pose.anchor,scale=config.scale} or nil
    end)
end
local function cancel_hd(message)
    hd_session.cancel(message)
    control_permit.revoke()
end
local function update_control_permit()
    -- Either the continuous capture or the avatar's pose stream owns Minecraft's controls.
    local binding=hd_session.binding() or pose_stream.binding()
    local pose=binding and active==bridge and bridge.pose() or nil
    if not pose or pose.state~='entered' or pose.session~=binding.session then return control_permit.stop() end
    local ok,context=pcall(engine.control_context)
    if not ok then
        control_permit.revoke()
        return false,'Native input permission unavailable: '..tostring(context)
    end
    return control_permit.tick(binding,context)
end
local function show_markers()
    if scene and scene.enabled() then return end
    local pawn = player()
    local kismet = UE.GetKismetSystemLibrary()
    for _,spec in ipairs(active.shapes()) do
        local ok, err = pcall(function()
            kismet:DrawDebugBox(pawn,spec.center,spec.extent,
                {R=0.1,G=1.0,B=0.4,A=1.0},{Pitch=0,Yaw=0,Roll=0},30.0,3.0)
        end)
        if not ok then log('Debug marker unavailable: '..tostring(err)); break end
    end
end
local function status()
    local pawn,world = player()
    local s = engine.snapshot()
    log(string.format('state=%s pawn=%s world=%s pos=(%.2f,%.2f,%.2f) capsule=(radius %.2f,half-height %.2f)',
        active.status(),pawn:GetFullName(),world:GetFullName(),s.position.X,s.position.Y,s.position.Z,s.radius,s.half_height))
    local timed,timing=pcall(native_timing,world)
    log(timed and timing or 'Native timing unavailable: '..tostring(timing))
    -- A short downward trace can identify support, but cannot prove native traversal by itself.
    local hit = {}
    local ok, was_hit = pcall(function()
        return UE.GetKismetSystemLibrary():LineTraceSingle(pawn,s.position,
            {X=s.position.X,Y=s.position.Y,Z=s.position.Z-s.half_height-30},0,false,{},0,hit,true,
            {R=0,G=1,B=0,A=1},{R=1,G=0,B=0,A=1},0)
    end)
    if ok and was_hit then
        local support = UE.GetActorFromHitResult(hit)
        if valid(support) then log('Downward trace support: '..support:GetFullName()) end
    end
    show_markers()
end
local function require_still()
    assert(capture,'Install with --bridge-dir to enable native image capture.')
    assert(not hd_session.busy(),'Continuous capture still owns its output.')
    assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before requesting a still.')
    assert(not stream.active(),'Stop native live capture before requesting a still.')
end
local function paired_still(profile)
    require_still()
    local pose=active==bridge and bridge.pose() or nil
    local link
    if pose and pose.state=='entered' then
        link={session=pose.session,ack=pose.ack,scale=config.scale,position=pose.position}
    end
    log(profile=='async' and 'Native HD async frame requested (one-shot pending files).'
        or profile=='hd-png' and 'Native HD PNG baseline capture started (1280x720 still).'
        or profile=='hd' and 'Native HD frame capture started (1280x720 still).' or 'Native frame capture started.')
    if profile=='async' then return capture.frameasync(link) end
    return capture.frame(link,profile)
end
local actions = {
    exporttest=function() return false,'Async export test is disabled after a native loader crash. Use F4 for the supported still capture.' end,
    exportasync=function()
        require_still()
        return capture.exportasync()
    end,
    exportbmp=function()
        require_still()
        return capture.exportbmp()
    end,
    visuals=function()
        assert(scene,'Install with --bridge-dir to enable native Minecraft visuals.')
        if scene.enabled() or scene.status()=='cleanup-required' then return scene.clear() end
        assert(not hd_session.busy(),'Continuous capture still owns its output.')
        assert(active==bridge,'Import and enter the Minecraft collision link first.')
        local stopped,why=stream.stop('native scene rendering requested')
        if not stopped then return false,why end
        return scene.toggle()
    end,
    texture=function()
        assert(visual,'Install with --bridge-dir to enable the native texture probe.')
        assert(not hd_session.busy(),'Continuous capture still owns its output.')
        return visual.toggle()
    end,
    live=function()
        assert(stream,'Install with --bridge-dir to enable native live capture.')
        assert(not hd_session.busy(),'Continuous capture still owns its output.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before starting live capture.')
        return stream.start()
    end,
    liveoff=function()
        assert(stream,'Native live capture is not configured.')
        control_permit.stop()
        if hd_session.busy() then return hd_session.stop('native stop requested') end
        return stream.stop()
    end,
    livetoggle=function()
        assert(stream,'Install with --bridge-dir to enable native live capture.')
        if stream.active() then return stream.stop() end
        assert(not hd_session.busy(),'Continuous capture still owns its output.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before starting live capture.')
        return stream.start()
    end,
    frame=function() return paired_still() end,
    frameasync=function() return paired_still('async') end,
    framehd=function() return paired_still('hd') end,
    framehdpng=function() return paired_still('hd-png') end,
    capture=function()
        require_still()
        return capture.capture('color')
    end,
    depth=function()
        require_still()
        return capture.capture('depth')
    end,
    posetest=function()
        assert(pose_recorder,'Install with --bridge-dir to enable the pose probe.')
        return pose_recorder.start()
    end,
    capturecleanup=function()
        assert(capture,'Capture is not configured.')
        control_permit.stop()
        local ok,why=hd_session.stop('cleanup requested')
        local old_ok,old_why=stream.stop('cleanup requested')
        return ok and old_ok,tostring(why)..'; '..tostring(old_why)
    end,
    create=function()
        assert(active.status()=='idle','Return and clean up the current scene first.')
        active=probe
        return probe.create()
    end,
    mirror=function()
        assert(bridge,'Install with --bridge-dir to enable the Minecraft link.')
        assert(active.status()=='idle','Return and clean up the current scene first.')
        active=bridge
        return bridge.create()
    end,
    enter=function() return active.enter() end,
    toggle=function()
        assert(active==probe,'Edit Minecraft blocks to update a linked scene.')
        return probe.toggle()
    end,
    restore=function()
        local success,messages=true,{}
        local function attempt(label,action,idle_return)
            local ran,ok,message=pcall(action)
            if ran and not ok and idle_return then ok=true; message='No active scene requires a return.' end
            success=success and ran and ok
            messages[#messages+1]=label..': '..tostring(ran and message or ok)
        end
        if control_permit then attempt('Input',function()
            local ok,why=control_permit.stop()
            return ok,why or 'Native input permission revoked.'
        end) end
        if scene then attempt('Scene',scene.clear) end
        if visual then attempt('Texture',visual.clear) end
        if hd_session then attempt('HD capture',function() return hd_session.stop('F11 return requested') end) end
        if stream then attempt('Capture',function() return stream.stop('return requested') end) end
        -- Each optional cleanup is independent of EVE's guarded return.
        local inspected,state=pcall(active.status)
        attempt('Return',active.restore,inspected and state=='idle')
        return success,table.concat(messages,'; ')
    end,
}
local function dispatch(name)
    ExecuteInGameThread(function()
        local ok, err = pcall(function()
            if name=='status' then status(); return end
            local action = actions[name]
            assert(action, 'Use status, create, mirror, enter, toggle, restore, texture, visuals, live, liveoff, frame, framehd, framehdpng, frameasync, exportbmp, exportasync, capture, depth, posetest or capturecleanup.')
            local success, message = action()
            log((success and 'OK: ' or 'STOP: ')..message)
            if success and (name=='create' or name=='mirror' or name=='enter' or name=='toggle') then show_markers() end
        end)
        if not ok then log('STOP: '..tostring(err)) end
    end)
end
RegisterKeyBind(Key.F1,function() dispatch('livetoggle') end)
RegisterKeyBind(Key.F2,function() dispatch('depth') end)
RegisterKeyBind(Key.F4,function() dispatch('framehd') end)
RegisterKeyBind(Key.F5,function() dispatch('mirror') end)
RegisterKeyBind(Key.F6,function() dispatch('status') end)
RegisterKeyBind(Key.F7,function() dispatch('create') end)
RegisterKeyBind(Key.F7,{ModifierKey.SHIFT},function() dispatch('texture') end)
RegisterKeyBind(Key.F8,function() dispatch('enter') end)
RegisterKeyBind(Key.F8,{ModifierKey.SHIFT},function() dispatch('visuals') end)
RegisterKeyBind(Key.F9,function() dispatch('toggle') end)
RegisterKeyBind(Key.F11,function() dispatch('restore') end)
RegisterConsoleCommandHandler('sbmc',function(_,parameters)
    dispatch(parameters[1] or 'status')
    return true
end)
if bridge then
    local pending=false
    local last_messages={}
    local function report_result(kind,success,message)
        if message and message~=last_messages[kind] then
            log((success and 'OK: ' or 'STOP: ')..message)
            last_messages[kind]=message
        elseif not message then last_messages[kind]=nil end
    end
    -- Keep Lua reads, state and native calls on one thread. The loader's
    -- recurring game-thread timer avoids sharing Lua state with LoopAsync.
    assert(type(LoopInGameThreadWithDelay)=='function','This loader requires game-thread timer support.')
    LoopInGameThreadWithDelay(200,function()
        if pending then return end
        pending=true
        local ok,why=pcall(function()
            local read_ok,packet=pcall(bridge.read)
            if not read_ok then
                stream.cancel('bridge snapshot read failed: '..tostring(packet))
                cancel_hd('bridge snapshot read failed')
            end
            local scene_packet
            if scene.enabled() then
                local scene_read,value=pcall(scene.read)
                if scene_read then scene_packet=value end
            end
            report_result('texture',visual.tick())
            local streamed,stream_result,stream_message=pcall(pose_stream.tick)
            if streamed then report_result('posestream',stream_result,stream_message)
            else report_result('posestream',false,'Pose stream paused: '..tostring(stream_result)) end
            local updated,update_error=pcall(bridge.tick,read_ok and packet or nil)
            if updated then
                report_result('scene',scene.tick(scene_packet))
                if not scene.enabled() then report_result('capture',stream.tick())
                elseif stream.active() then report_result('capture',stream.stop('native scene rendering is active')) end
            else
                control_permit.stop()
                report_result('hd',hd_session.stop('bridge tick failed'))
                report_result('scene',scene.clear('bridge tick failed'))
                report_result('capture',stream.stop('bridge tick failed: '..tostring(update_error)))
            end
        end)
        pending=false
        if not ok then
            stream.cancel('game-thread callback failed')
            cancel_hd('terrain game-thread callback failed')
            log('Bridge/capture callback failed: '..tostring(why))
        end
    end)
    local hd_pending=false
    -- One bounded line per ten native seconds while a link is entered, with or
    -- without capture, so one session compares native cadence at a fixed size.
    local cadence={}
    local function report_cadence(frame)
        local second=os.time()
        if second==cadence.second then return end
        cadence.second=second
        local pose=active==bridge and bridge.pose() or nil
        if not pose or pose.state~='entered' then cadence.start=nil; return end
        local _,world=player()
        local seconds,start=real_seconds(world),cadence.start
        if not start or seconds<start.seconds or frame<start.frame then
            cadence.start={frame=frame,seconds=seconds}; raw.cadence(); return
        end
        local elapsed=seconds-start.seconds
        if elapsed<10 then return end
        local generations=raw.cadence()
        local h=generations.histogram
        log(string.format('Cadence: %.1f native FPS over %.1f s; capture %s; %d generations, %.2f native frames each [1:%d 2:%d 3:%d 4:%d 5+:%d]',
            (frame-start.frame)/elapsed,elapsed,hd_session.active() and 'live' or 'idle',generations.generations,
            generations.generations>0 and generations.frames/generations.generations or 0,h[1],h[2],h[3],h[4],h[5]))
        cadence.start={frame=frame,seconds=seconds}
    end
    -- The loader starts its delay after callback completion. A short timer
    -- checks the next native frame; raw capture still owns only one pending frame.
    LoopInGameThreadWithDelay(1,function()
        if hd_pending then return end
        hd_pending=true
        local ran,err=pcall(function()
            local frame=native_frame_count()
            if frame==last_hd_frame then return end
            last_hd_frame=frame
            local read_ok,packet=pcall(hd_session.read)
            if not read_ok then cancel_hd('capture request read failed') end
            report_result('hd',hd_session.tick(read_ok and packet or {}))
            report_result('input',update_control_permit())
            report_result('pose',pose_recorder.tick(frame))
            pcall(report_cadence,frame) -- Optional diagnostics never cancel capture or input.
        end)
        hd_pending=false
        if not ran then
            cancel_hd('capture callback failed')
            log('Continuous HD callback failed: '..tostring(err))
        end
    end)

end
log('Loaded revision 38; idle. Game-thread timers active. Coordinate basis: UE(X,Y,Z) -> MC(X,Z,Y). F1 bounded live capture, F2 depth still, F4 HD paired still, F5 Minecraft import, F6 status/timing, F7 diagnostic fixture, Shift+F7 texture probe, F8 enter, Shift+F8 native visuals, F9 obstacle, F11 return/cleanup. Console sbmc framehdpng selects the HD PNG baseline; sbmc exportbmp compares synchronous color exports; sbmc frameasync requests one pending HD frame; it shares one async attempt per Lua lifetime with sbmc exportasync. Minecraft /stellarview livehd controls continuous HD; F11 stops it and drains pending output. Minecraft /stellarview play additionally requires the matching game-local input adapter; console sbmc posetest records the EVE pose for about 15 seconds; Minecraft /stellaravatar live streams it through the native adapter and /stellaravatar play drives EVE without capture; native control permission is revoked outside the entered playable context. No geometry or capture created at startup.')
