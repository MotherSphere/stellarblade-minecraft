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
function engine.snapshot()
    local pawn,world = player()
    local capsule = pawn.CapsuleComponent
    assert(valid(capsule), 'The controlled pawn has no capsule; no scene changes made.')
    return {pawn_id=pawn:GetAddress(),world_id=world:GetAddress(),
        position=vec(pawn:K2_GetActorLocation()), rotation=rot(pawn:K2_GetActorRotation()),
        half_height=capsule:GetScaledCapsuleHalfHeight(),radius=capsule:GetScaledCapsuleRadius()}
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
local visual
local scene
local configured,config = pcall(require,'bridge_config')
if configured then
    local Terrain = require('terrain')
    bridge = require('bridge').new(Terrain.new(engine,config.scale),config.directory,log)
    capture = require('capture').new(require('capture_adapter').new(),config.directory)
    visual = require('visual_probe').new(require('visual_probe_adapter').new(),config.directory)
    scene = require('native_scene').new(require('scene_adapter').new(),bridge,config.directory,config.scale)
    stream = require('capture_stream').new(capture,function()
        return active==bridge and bridge.pose() or nil
    end,config.scale)
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
    local timed,timing=pcall(function()
        local system=StaticFindObject('/Script/Engine.Default__KismetSystemLibrary')
        local gameplay=StaticFindObject('/Script/Engine.Default__GameplayStatics')
        assert(valid(system) and valid(gameplay),'native timing libraries are unavailable')
        local frames,seconds=system:GetFrameCount(),gameplay:GetRealTimeSeconds(world)
        assert(type(frames)=='number' and frames>=0 and type(seconds)=='number' and seconds>=0 and seconds<1e15,
            'native timing counters are unavailable')
        return string.format('native_frame_count=%d native_real_seconds=%.6f',frames,seconds)
    end)
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
local actions = {
    visuals=function()
        assert(scene,'Install with --bridge-dir to enable native Minecraft visuals.')
        if scene.enabled() or scene.status()=='cleanup-required' then return scene.clear() end
        assert(active==bridge,'Import and enter the Minecraft collision link first.')
        local stopped,why=stream.stop('native scene rendering requested')
        if not stopped then return false,why end
        return scene.toggle()
    end,
    texture=function()
        assert(visual,'Install with --bridge-dir to enable the native texture probe.')
        return visual.toggle()
    end,
    live=function()
        assert(stream,'Install with --bridge-dir to enable native live capture.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before starting live capture.')
        return stream.start()
    end,
    liveoff=function()
        assert(stream,'Native live capture is not configured.')
        return stream.stop()
    end,
    livetoggle=function()
        assert(stream,'Install with --bridge-dir to enable native live capture.')
        if stream.active() then return stream.stop() end
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before starting live capture.')
        return stream.start()
    end,
    frame=function()
        assert(capture,'Install with --bridge-dir to enable native image capture.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before requesting a still.')
        assert(not stream.active(),'Stop native live capture before requesting a still.')
        local pose=active==bridge and bridge.pose() or nil
        local link
        if pose and pose.state=='entered' then
            link={session=pose.session,ack=pose.ack,scale=config.scale,position=pose.position}
        end
        log('Native frame capture started.')
        return capture.frame(link)
    end,
    capture=function()
        assert(capture,'Install with --bridge-dir to enable native image capture.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before requesting a still.')
        assert(not stream.active(),'Stop native live capture before requesting a still.')
        return capture.capture('color')
    end,
    depth=function()
        assert(capture,'Install with --bridge-dir to enable native image capture.')
        assert(not scene.enabled() and scene.status()=='idle','Disable and clean up native scene visuals before requesting a still.')
        assert(not stream.active(),'Stop native live capture before requesting a still.')
        return capture.capture('depth')
    end,
    capturecleanup=function()
        assert(capture,'Capture is not configured.')
        return stream.stop('cleanup requested')
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
        if scene then attempt('Scene',scene.clear) end
        if visual then attempt('Texture',visual.clear) end
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
            assert(action, 'Use status, create, mirror, enter, toggle, restore, texture, visuals, live, liveoff, frame, capture, depth or capturecleanup.')
            local success, message = action()
            log((success and 'OK: ' or 'STOP: ')..message)
            if success and (name=='create' or name=='mirror' or name=='enter' or name=='toggle') then show_markers() end
        end)
        if not ok then log('STOP: '..tostring(err)) end
    end)
end
RegisterKeyBind(Key.F1,function() dispatch('livetoggle') end)
RegisterKeyBind(Key.F2,function() dispatch('depth') end)
RegisterKeyBind(Key.F4,function() dispatch('frame') end)
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
    LoopAsync(200,function()
        if pending then return false end
        pending=true
        local read_ok,packet=pcall(bridge.read)
        if not read_ok then stream.cancel('bridge snapshot read failed: '..tostring(packet)) end
        local scene_packet
        if scene.enabled() then
            local scene_read,value=pcall(scene.read)
            if scene_read then scene_packet=value end
        end
        local scheduled,err=pcall(ExecuteInGameThread,function()
            local ok,why=pcall(function()
                report_result('texture',visual.tick())
                local updated,update_error=pcall(bridge.tick,read_ok and packet or nil)
                if updated then
                    report_result('scene',scene.tick(scene_packet))
                    if not scene.enabled() then report_result('capture',stream.tick())
                    elseif stream.active() then report_result('capture',stream.stop('native scene rendering is active')) end
                else
                    report_result('scene',scene.clear('bridge tick failed'))
                    report_result('capture',stream.stop('bridge tick failed: '..tostring(update_error)))
                end
            end)
            -- Keep one pending callback until native export and cleanup have finished.
            pending=false
            if not ok then
                stream.cancel('game-thread callback failed')
                log('Bridge/capture callback failed: '..tostring(why))
            end
        end)
        if not scheduled then
            stream.cancel('game-thread scheduling failed')
            pending=false
            log('Bridge schedule failed: '..tostring(err))
        end
        return false
    end)
end
log('Loaded revision 11; idle. Coordinate basis: UE(X,Y,Z) -> MC(X,Z,Y). F1 bounded live capture, F2 depth still, F4 paired frame, F5 Minecraft import, F6 status/timing, F7 diagnostic fixture, Shift+F7 texture probe, F8 enter, Shift+F8 native visuals, F9 obstacle, F11 return/cleanup. No geometry or capture created at startup.')
