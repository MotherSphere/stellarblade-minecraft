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
-- Lab sites (Unreal X/Y, cm) by persistent level, where World Composition streams none of its tiles but the sky's
-- E05_ENV, the nearest gameplay tile 5.8 km away and the world edge 3 km away; other levels keep the lab above EVE.
local LAB_SITES = {E05_GreatDesert_P={X=-750000,Y=-750000}}
function engine.snapshot()
    local pawn,world = player()
    local capsule = pawn.CapsuleComponent
    assert(valid(capsule), 'The controlled pawn has no capsule; no scene changes made.')
    return {pawn_id=pawn:GetAddress(),world_id=world:GetAddress(),
        position=vec(pawn:K2_GetActorLocation()), rotation=rot(pawn:K2_GetActorRotation()),
        half_height=capsule:GetScaledCapsuleHalfHeight(),radius=capsule:GetScaledCapsuleRadius(),
        site=LAB_SITES[world:GetFullName():match('([%w_]+)$')],
        -- Stellar Blade lets her pass through characters (its evades): mob walls step aside too.
        ignoring=(function() local ok,v=pcall(function() return pawn.bIgnoreCharacterCollision end); return ok and v==true end)()}
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

-- The diagnostic cube, kept once found: StaticFindObject never finds it in this build, even loaded and in use, so each
-- visual repeated the failed find and a LoadAsset (an asset-registry lookup and a synchronous load), about 20 ms, a
-- whole frame per box. UE4SS invalidates the kept object if the engine deletes it, which brings one more load.
local CUBE = '/Engine/BasicShapes/Cube.Cube'
local cube, cube_missing
local function cube_mesh()
    if not valid(cube) and not cube_missing then
        cube = StaticFindObject(CUBE)
        if not valid(cube) then
            local ok, loaded = pcall(LoadAsset, CUBE)
            cube = ok and loaded or nil
        end
        cube_missing = not valid(cube)
        if cube_missing then log('Optional cube visuals unavailable until Lua reloads: '..CUBE..' could not be loaded.') end
    end
    if valid(cube) then return cube end
end
-- Visuals are optional. Collision always belongs to the TriggerBox, never this mesh. A reused handle keeps its visual.
local function add_visual(world, spec, handle)
    local visual = handle.visual
    if not valid(visual) then
        local mesh = cube_mesh()
        if not mesh then return end
        local class = StaticFindObject('/Script/Engine.StaticMeshActor')
        if not valid(class) then return end
        visual = world:SpawnActor(class,spec.center,{Pitch=0,Yaw=0,Roll=0})
        if not valid(visual) then return end
        -- Keep ownership even if optional setup fails.
        handle.visual = visual
        local component = visual.StaticMeshComponent
        assert(valid(component), 'Optional visual has no StaticMeshComponent.')
        component:SetMobility(2)
        component:SetCollisionEnabled(0)
        visual:SetActorEnableCollision(false,false)
        assert(component:SetStaticMesh(mesh), 'Optional cube visual could not be set.')
    else
        visual:K2_SetActorLocation(spec.center,false,{},true)
    end
    visual:SetActorScale3D({X=spec.extent.X/50,Y=spec.extent.Y/50,Z=spec.extent.Z/50})
    visual:SetActorHiddenInGame(spec.visuals_visible==false)
end
function engine.spawn(spec)
    local _,world = player()
    local class = StaticFindObject('/Script/Engine.TriggerBox')
    assert(valid(class), 'TriggerBox class unavailable in this game build.')
    local actor = world:SpawnActor(class,spec.center,{Pitch=0,Yaw=0,Roll=0})
    assert(valid(actor), 'TriggerBox could not be spawned.')
    if spec.role~='minecraft' then -- Up to a thousand boxes per import; the terrain summary counts them.
        log(string.format('Spawned %s at %.1f %.1f %.1f, half-extents %.1f %.1f %.1f',
            spec.role,spec.center.X,spec.center.Y,spec.center.Z,spec.extent.X,spec.extent.Y,spec.extent.Z))
    end
    -- Register ownership in the lifecycle before any fallible component setup.
    return {actor=actor}
end
-- A new or parked handle (engine.park): sized and placed before its collision is enabled.
function engine.configure(handle, spec)
    local actor = handle.actor
    local box = actor.CollisionComponent
    assert(valid(box) and box:IsA('/Script/Engine.BoxComponent'), 'TriggerBox has no BoxComponent.')
    box:SetMobility(2)
    box:SetGenerateOverlapEvents(false)
    box:SetBoxExtent(spec.extent,true)
    assert(actor:K2_SetActorLocation(spec.center,false,{},true), 'A terrain actor could not be placed.')
    box:SetCollisionObjectType(1) -- WorldDynamic: EVE's capsule blocks on it, as the in-game avatar sessions showed.
    box:SetCollisionResponseToAllChannels(2) -- Block
    box:SetCollisionEnabled(3) -- QueryAndPhysics
    -- Stellar Blade adds inFastUpdate; use the non-fast path for this experiment.
    actor:SetActorEnableCollision(true,false)
    assert(box:GetCollisionEnabled()==3, 'Collision mode was not accepted.')
    local shown, why = pcall(add_visual,actor:GetWorld(),spec,handle)
    if not shown then log('Optional cube visual unavailable: '..tostring(why)) end
end
-- Retires a handle for reuse: collision off first, then hidden and moved 100 m up.
function engine.park(handle)
    local actor = handle.actor
    local box = actor.CollisionComponent
    box:SetCollisionEnabled(0)
    actor:SetActorEnableCollision(false,false)
    assert(box:GetCollisionEnabled()==0, 'A parked terrain actor kept its collision.')
    if valid(handle.visual) then handle.visual:SetActorHiddenInGame(true) end
    local at = actor:K2_GetActorLocation()
    assert(actor:K2_SetActorLocation({X=at.X,Y=at.Y,Z=at.Z+10000},false,{},true), 'A terrain actor could not be parked.')
end
function engine.destroy(handle)
    if valid(handle.visual) then handle.visual:K2_DestroyActor() end
    if valid(handle.actor) then handle.actor:K2_DestroyActor() end
end
function engine.set_visual_visible(handle,visible)
    if valid(handle.visual) then handle.visual:SetActorHiddenInGame(not visible) end
end
-- Minecraft's mobs (mobs.lua): a TriggerBox that blocks only EVE's capsule channel, so her movement stops at it while
-- cameras, traces and attacks ignore it; no overlap events and no visual. It waits 100 m up, without collision.
function engine.blocker_spawn()
    local pawn,world = player()
    local class = StaticFindObject('/Script/Engine.TriggerBox')
    assert(valid(class), 'TriggerBox class unavailable in this game build.')
    local at = pawn:K2_GetActorLocation()
    local actor = world:SpawnActor(class,{X=at.X,Y=at.Y,Z=at.Z+10000},{Pitch=0,Yaw=0,Roll=0})
    assert(valid(actor), 'TriggerBox could not be spawned.')
    return actor
end
-- The caller owns the spawned actor before this fallible setup, so a failure leaves it to the caller's release.
function engine.blocker_configure(actor)
    local capsule = player().CapsuleComponent
    assert(valid(capsule), 'EVE has no capsule.')
    local channel = capsule:GetCollisionObjectType()
    local box = actor.CollisionComponent
    assert(valid(box) and box:IsA('/Script/Engine.BoxComponent'), 'TriggerBox has no BoxComponent.')
    box:SetMobility(2)
    box:SetGenerateOverlapEvents(false)
    box:SetCollisionEnabled(0)
    box:SetCollisionObjectType(1) -- WorldDynamic, which EVE's capsule blocks, as it blocks the terrain.
    box:SetCollisionResponseToAllChannels(0) -- Ignore
    box:SetCollisionResponseToChannel(channel,2) -- Block: her capsule's own channel only (24 in the pinned build).
    pcall(function() box.CanCharacterStepUpOn = 0 end) -- ECB_No: she does not walk up onto a small mob.
    actor:SetActorEnableCollision(true,false)
    return 'blocks channel '..tostring(channel)
end
-- A box spec, or nil to leave it where it is; solid false takes collision away before moving, so a blocking box never
-- lands inside EVE. QueryOnly: her movement sweeps are queries, and no physics body reacts to it.
function engine.blocker_place(actor, spec, solid)
    local box = actor.CollisionComponent
    if not solid then box:SetCollisionEnabled(0) end
    if spec then
        box:SetBoxExtent(spec.extent,true)
        assert(actor:K2_SetActorLocation(spec.center,false,{},true), 'A mob wall could not be moved.')
    end
    if solid then box:SetCollisionEnabled(1) end
    assert(box:GetCollisionEnabled()==(solid and 1 or 0), 'A mob wall kept the wrong collision mode.')
end
function engine.blocker_destroy(actor) if valid(actor) then actor:K2_DestroyActor() end end
function engine.move(position, rotation)
    local pawn = player()
    local hit = {}
    local moved = pawn:K2_SetActorLocationAndRotation(position,rotation,false,hit,true)
    if not moved then return false end
    local actual = pawn:K2_GetActorLocation()
    return math.abs(actual.X-position.X)<1 and math.abs(actual.Y-position.Y)<1 and math.abs(actual.Z-position.Z)<1
end
local function trace_down(pawn, position, half_height, hit)
    return UE.GetKismetSystemLibrary():LineTraceSingle(pawn,position,
        {X=position.X,Y=position.Y,Z=position.Z-half_height-30},0,false,{},0,hit,true,
        {R=0,G=1,B=0,A=1},{R=1,G=0,B=0,A=1},0)
end
-- Whether anything is under a capsule centre: from the remote lab site, only Stellar Blade's streamed ground can be.
function engine.ground_at(position, half_height) return trace_down(player(),position,half_height,{})==true end
-- Stellar Blade's own warp pin (UWorld bEnableFixedStreamingLocation 0x793, FixedStreamingLocation 0x794, read by World
-- Composition and its level volumes, RVA 0x46a48a0): its world streams around position instead of its camera; nil
-- clears it. Writes only what differs; if_unpinned leaves any pin in place, such as a warp's at its destination.
function engine.stream_at(position, if_unpinned)
    local _,world = player()
    if if_unpinned and world.bEnableFixedStreamingLocation then return end
    local function at(wanted)
        local now = world.FixedStreamingLocation
        return math.abs(now.X-wanted.X)<1 and math.abs(now.Y-wanted.Y)<1 and math.abs(now.Z-wanted.Z)<1
    end
    if position and not at(position) then
        pcall(function() world.FixedStreamingLocation = {X=position.X,Y=position.Y,Z=position.Z} end)
        if not at(position) then -- The vector one component at a time if the table did not reach it.
            local now = world.FixedStreamingLocation
            now.X,now.Y,now.Z = position.X,position.Y,position.Z
            assert(at(position), 'Stellar Blade did not accept the streaming location.')
        end
    end
    if world.bEnableFixedStreamingLocation ~= (position~=nil) then
        world.bEnableFixedStreamingLocation = position~=nil
        assert(world.bEnableFixedStreamingLocation == (position~=nil), 'Stellar Blade did not accept the streaming pin.')
    end
end
-- Paused, Stellar Blade's World Composition requests no tiles (UWorld::Tick, RVA 0x42d3680).
function engine.paused()
    local _,world = player()
    local gameplay = StaticFindObject('/Script/Engine.Default__GameplayStatics')
    assert(valid(gameplay), 'Native pause state is unavailable.')
    return gameplay:IsGamePaused(world)==true
end
-- Blocks until the streaming levels Stellar Blade requested are loaded and visible (UWorld::FlushLevelStreaming).
function engine.flush_streaming()
    local _,world = player()
    local gameplay = StaticFindObject('/Script/Engine.Default__GameplayStatics')
    assert(valid(gameplay), 'Level streaming flush unavailable.')
    gameplay:FlushLevelStreaming(world)
end
-- Stellar Blade's interval autosave (ASBPlayerController.bUseAutoSave): sets it and returns the previous value.
function engine.autosave(enabled)
    local controller = UE.GetPlayerController()
    assert(valid(controller), 'No player controller for the autosave switch.')
    local old = controller.bUseAutoSave
    assert(type(old)=='boolean', 'Stellar Blade autosave switch unavailable.')
    controller.bUseAutoSave = enabled
    assert(controller.bUseAutoSave==enabled, 'Stellar Blade did not accept the autosave switch.')
    return old
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
local combat
local lock
local mobs
local hud
local world_render
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
            and world_render.restore() -- Minecraft's /stellarview livehd: SB draws its world before the first frame.
    end,nil,raw)
    control_permit=require('control_permit').new(config.directory)
    pose_recorder=require('pose_probe').new(require('pose_adapter').new(),config.directory,os.clock,log)
    -- Minecraft's avatar view requests the stream; the entered link fixes the Unreal-to-Minecraft placement.
    local function entered_link()
        local pose=active==bridge and bridge.pose() or nil
        return pose and pose.state=='entered' and pose.anchor and {session=pose.session,anchor=pose.anchor,scale=config.scale} or nil
    end
    pose_stream=require('pose_stream').new(require('pose_adapter').new(),config.directory,os.time,log,entered_link)
    -- Minecraft's combat verdicts at EVE's impacts; os.clock is wall time under the MSVC runtime.
    combat=require('combat_events').new(require('pose_adapter').new(),config.directory,os.time,os.clock,log,entered_link)
    -- Minecraft's lock-on target: Stellar Blade's own lock-on on a hidden proxy, so EVE faces and strafes around it.
    lock=require('lock_on').new(require('pose_adapter').new(),config.directory,os.time,os.clock,log,entered_link)
    -- Minecraft's mobs near EVE: invisible walls that stop her as Minecraft's own blocks do.
    mobs=require('mobs').new(engine,config.directory,os.time,os.clock,log,entered_link)
    -- Stellar Blade's HUD item slots and item uses, for Minecraft's HUD and its item effects.
    hud=require('hud_items').new(require('pose_adapter').new(),config.directory,os.time,log,entered_link)
    -- Stellar Blade's 3D world drawing, off while only the avatar stream needs EVE; every capture restores it first. It
    -- samples the ponytail's cloth state meanwhile.
    world_render=require('world_render').new(log,os.clock,require('pose_adapter').new().cloth)
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
    if world_render then log('Render: '..world_render.status()) end
    log(string.format('state=%s pawn=%s world=%s pos=(%.2f,%.2f,%.2f) capsule=(radius %.2f,half-height %.2f)',
        active.status(),pawn:GetFullName(),world:GetFullName(),s.position.X,s.position.Y,s.position.Z,s.radius,s.half_height))
    -- How much of its world Stellar Blade keeps loaded: about 1 tile (the sky) at the remote lab site.
    local counted,tiles = pcall(function()
        local loaded,total = 0,0
        world.WorldComposition.TilesStreaming:ForEach(function(_,tile)
            total = total+1
            if tile:get():IsLevelLoaded() then loaded = loaded+1 end
        end)
        local at = world.FixedStreamingLocation
        return string.format('World Composition tiles loaded: %d of %d; streaming %s',loaded,total,
            world.bEnableFixedStreamingLocation and string.format('pinned at (%.0f,%.0f)',at.X,at.Y) or 'follows the camera')
    end)
    log(counted and tiles or 'World Composition tiles unavailable: '..tostring(tiles))
    local timed,timing=pcall(native_timing,world)
    log(timed and timing or 'Native timing unavailable: '..tostring(timing))
    -- A short downward trace can identify support, but cannot prove native traversal by itself.
    local hit = {}
    local ok, was_hit = pcall(trace_down,pawn,s.position,s.half_height,hit)
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
    assert(world_render.restore())
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
        assert(world_render.restore())
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
        assert(world_render.restore())
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
        assert(world_render.restore())
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
        if lock then attempt('Lock-on',function() return lock.release('F11 return requested') end) end
        if mobs then attempt('Mob walls',function() return mobs.release('F11 return requested') end) end
        -- Each optional cleanup is independent of EVE's guarded return.
        local inspected,state=pcall(active.status)
        attempt('Return',active.restore,inspected and state=='idle')
        return success,table.concat(messages,'; ')
    end,
}
-- One game-thread path for keys, console commands and Minecraft's session requests; returns success and its message.
local function run(name)
    local ok, success, message = pcall(function()
        if name=='status' then status(); return true,'Status logged.' end
        local action = actions[name]
        assert(action, 'Use status, create, mirror, enter, toggle, restore, texture, visuals, live, liveoff, frame, framehd, framehdpng, frameasync, exportbmp, exportasync, capture, depth, posetest or capturecleanup.')
        local success, message = action()
        log((success and 'OK: ' or 'STOP: ')..message)
        if success and (name=='create' or name=='mirror' or name=='enter' or name=='toggle') then
            local shown, why = pcall(show_markers)
            if not shown then log('STOP: '..tostring(why)) end
        end
        return success, message
    end)
    if not ok then log('STOP: '..tostring(success)); return false, tostring(success) end
    return success, message
end
local function dispatch(name) ExecuteInGameThread(function() run(name) end) end
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
    -- Minecraft's /stellar import, enter and return: F5, F8 and F11 through the same run, without Stellar Blade's window.
    local requests=require('requests').new(config.directory,os.time,log,function()
        local pose=active==bridge and bridge.pose() or nil
        return pose and pose.session
    end,function()
        local packet=bridge.read()
        return packet and packet.session
    end,run)
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
                world_render.tick(streamed and pose_stream.binding()~=nil and not hd_session.busy() and not stream.active()
                    and not scene.enabled(),pose_stream.components())
            else
                control_permit.stop()
                report_result('hd',hd_session.stop('bridge tick failed'))
                report_result('scene',scene.clear('bridge tick failed'))
                report_result('capture',stream.stop('bridge tick failed: '..tostring(update_error)))
                world_render.restore()
            end
            local asked,ask_error=pcall(requests.tick) -- After the bridge's own tick; never cancels capture or input.
            if not asked then report_result('request',false,'Session requests paused: '..tostring(ask_error)) end
            local shown,hud_error=pcall(hud.tick) -- Nor do the HUD slots.
            if not shown then report_result('hud',false,'HUD slots paused: '..tostring(hud_error)) end
        end)
        pending=false
        if not ok then
            world_render.restore()
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
            -- A terrain revision places boxes within a per-frame budget (window moves are large); the bridge logs how it ends.
            local imported,import_error=pcall(bridge.step)
            if not imported then report_result('terrain',false,'Terrain import paused: '..tostring(import_error)) end
            report_result('input',update_control_permit())
            report_result('pose',pose_recorder.tick(frame))
            local fought,fight_error=pcall(combat.tick) -- Combat answers never cancel capture or input.
            if not fought then report_result('combat',false,'Combat events paused: '..tostring(fight_error)) end
            local locked,lock_error=pcall(lock.tick) -- Nor does the lock-on.
            if not locked then report_result('lock',false,'Lock-on paused: '..tostring(lock_error)) end
            local walled,wall_error=pcall(mobs.tick) -- Nor do the mob walls.
            if not walled then report_result('mobs',false,'Mob walls paused: '..tostring(wall_error)) end
            pcall(report_cadence,frame) -- Optional diagnostics never cancel capture or input.
        end)
        hd_pending=false
        if not ran then
            cancel_hd('capture callback failed')
            log('Continuous HD callback failed: '..tostring(err))
        end
    end)

end
log('Loaded revision 57; idle. Game-thread timers active. Coordinate basis: UE(X,Y,Z) -> MC(X,Z,Y). F1 bounded live capture, F2 depth still, F4 HD paired still, F5 Minecraft import, F6 status/timing, F7 diagnostic fixture, Shift+F7 texture probe, F8 enter, Shift+F8 native visuals, F9 obstacle, F11 return/cleanup; Minecraft /stellar import, enter and return run F5, F8 and F11. Console sbmc framehdpng selects the HD PNG baseline; sbmc exportbmp compares synchronous color exports; sbmc frameasync requests one pending HD frame; it shares one async attempt per Lua lifetime with sbmc exportasync. Minecraft /stellarview livehd controls continuous HD; F11 stops it and drains pending output. Minecraft /stellarview play additionally requires the matching game-local input adapter; console sbmc posetest records the EVE pose for about 15 seconds; Minecraft /stellaravatar live streams it through the native adapter and /stellaravatar play drives EVE without capture; native control permission is revoked outside the entered playable context. No geometry or capture created at startup.')
