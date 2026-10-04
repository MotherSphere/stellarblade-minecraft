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
    visual:SetActorEnableCollision(false)
    assert(component:SetStaticMesh(mesh), 'Optional cube visual could not be set.')
    visual:SetActorScale3D({X=spec.extent.X/50,Y=spec.extent.Y/50,Z=spec.extent.Z/50})
    visual:SetActorHiddenInGame(false)
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
    actor:SetActorEnableCollision(true)
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
function engine.move(position, rotation)
    local pawn = player()
    local hit = {}
    local moved = pawn:K2_SetActorLocationAndRotation(position,rotation,false,hit,true)
    if not moved then return false end
    local actual = pawn:K2_GetActorLocation()
    return math.abs(actual.X-position.X)<1 and math.abs(actual.Y-position.Y)<1 and math.abs(actual.Z-position.Z)<1
end

local probe = Probe.new(engine)
local function show_markers()
    local pawn = player()
    local kismet = UE.GetKismetSystemLibrary()
    for _,spec in ipairs(probe.shapes()) do
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
        probe.status(),pawn:GetFullName(),world:GetFullName(),s.position.X,s.position.Y,s.position.Z,s.radius,s.half_height))
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
local actions = {create=probe.create,enter=probe.enter,toggle=probe.toggle,restore=probe.restore}
local function dispatch(name)
    ExecuteInGameThread(function()
        local ok, err = pcall(function()
            if name=='status' then status(); return end
            local action = actions[name]
            assert(action, 'Use status, create, enter, toggle or restore.')
            local success, message = action()
            log((success and 'OK: ' or 'STOP: ')..message)
            if success and name~='restore' then show_markers() end
        end)
        if not ok then log('STOP: '..tostring(err)) end
    end)
end
RegisterKeyBind(Key.F6,{ModifierKey.CONTROL},function() dispatch('status') end)
RegisterKeyBind(Key.F7,{ModifierKey.CONTROL},function() dispatch('create') end)
RegisterKeyBind(Key.F8,{ModifierKey.CONTROL},function() dispatch('enter') end)
RegisterKeyBind(Key.F9,{ModifierKey.CONTROL},function() dispatch('toggle') end)
RegisterKeyBind(Key.F10,{ModifierKey.CONTROL},function() dispatch('restore') end)
RegisterConsoleCommandHandler('sbmc',function(_,parameters)
    dispatch(parameters[1] or 'status')
    return true
end)
log('Loaded revision 2; idle. Ctrl+F6 status, Ctrl+F7 create, Ctrl+F8 enter, Ctrl+F9 obstacle, Ctrl+F10 return/cleanup. No geometry created at startup.')
