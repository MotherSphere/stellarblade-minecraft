-- Owns only this probe's temporary scene. Unreal calls live in main.lua.
local Probe = {}

function Probe.new(engine)
    local fixture
    local api = {}
    local function same_context()
        local now = engine.snapshot()
        assert(now.pawn_id == fixture.origin.pawn_id and now.world_id == fixture.origin.world_id,
            'Player/world changed. Do not restore an old position; restart the game to reset the probe.')
        return now
    end
    local function cleanup()
        local remaining, errors = {}, {}
        for i = #fixture.actors, 1, -1 do
            local item = fixture.actors[i]
            local ok, err = pcall(function()
                engine.destroy(item.actor)
            end)
            if not ok then
                remaining[#remaining+1] = item
                errors[#errors+1] = tostring(err)
            end
        end
        fixture.actors = remaining
        if #remaining == 0 then fixture = nil end
        return #errors == 0, table.concat(errors, '; ')
    end
    local function protected(fn)
        return function()
            local ok, value = pcall(fn)
            if not ok then return false, tostring(value) end
            return true, value
        end
    end
    api.create = protected(function()
        assert(not fixture, 'A fixture already exists; return and clean up first.')
        local origin = engine.snapshot()
        assert(origin.half_height > 0 and origin.radius > 0, 'A valid EVE capsule is required.')
        local x,y,z = origin.position.X, origin.position.Y, origin.position.Z + 600
        local function box(role, dx, dy, dz, ex, ey, ez)
            return {role=role, center={X=x+dx,Y=y+dy,Z=z+dz}, extent={X=ex,Y=ey,Z=ez}}
        end
        local specs = {
            box('floor',0,0,0,800,800,25),
            box('wall',-800,0,175,15,800,150),
            box('wall',800,0,175,15,800,150),
            box('wall',0,-800,175,800,15,150),
            box('wall',0,800,175,800,15,150),
            box('step',-50,300,45,120,120,20),
            box('obstacle',200,0,125,60,180,100),
        }
        fixture = {origin=origin, actors={}, entered=false,
            entry={X=x-500,Y=y,Z=z+25+origin.half_height+5}, obstacle=specs[#specs]}
        for _,spec in ipairs(specs) do
            local ok, actor = pcall(engine.spawn, spec)
            if not ok or not actor then
                local reason = tostring(actor or 'spawn returned no actor')
                local cleaned, err = cleanup()
                error(reason .. (cleaned and '; creation rolled back' or '; cleanup must be retried: '..err))
            end
            fixture.actors[#fixture.actors+1] = {actor=actor,spec=spec}
        end
        return 'Fixture ready. Entry is separate; confirm the area above EVE is clear first.'
    end)
    api.enter = protected(function()
        assert(fixture, 'Create the fixture first.')
        same_context()
        assert(not fixture.entered, 'Already entered; native movement is in control.')
        for _,item in ipairs(fixture.actors) do
            assert(engine.valid(item.actor), 'A fixture actor is missing; do not enter.')
        end
        assert(engine.move(fixture.entry,fixture.origin.rotation), 'Entry teleport failed; fixture retained.')
        fixture.entered = true
        return 'Entered once. Native movement remains unchanged. Return before saving or changing levels.'
    end)
    api.toggle = protected(function()
        assert(fixture, 'Create the fixture first.')
        local now = same_context()
        for i,item in ipairs(fixture.actors) do
            if item.spec.role == 'obstacle' then
                engine.destroy(item.actor)
                table.remove(fixture.actors,i)
                return 'Obstacle removed; floor and walls remain.'
            end
        end
        local spec = fixture.obstacle
        local p,e,c = now.position,spec.extent,spec.center
        local overlaps = math.abs(p.X-c.X) <= e.X+now.radius
            and math.abs(p.Y-c.Y) <= e.Y+now.radius
            and math.abs(p.Z-c.Z) <= e.Z+now.half_height
        assert(not overlaps, 'Move away from the obstacle location before recreating it.')
        local actor = engine.spawn(spec)
        assert(actor, 'Obstacle recreation failed.')
        fixture.actors[#fixture.actors+1] = {actor=actor,spec=spec}
        return 'Obstacle recreated.'
    end)
    api.restore = protected(function()
        assert(fixture, 'No fixture is active.')
        same_context()
        if fixture.entered then
            assert(engine.move(fixture.origin.position,fixture.origin.rotation),
                'Return teleport failed; supporting floor retained. Retry return.')
            fixture.entered = false
        end
        local ok, err = cleanup()
        assert(ok, 'Some actors remain; retry cleanup: '..err)
        return 'Returned and removed owned fixture actors.'
    end)
    function api.status()
        return fixture and (fixture.entered and 'entered' or 'ready') or 'idle'
    end
    function api.shapes()
        local specs = {}
        if fixture then
            for _,item in ipairs(fixture.actors) do specs[#specs+1] = item.spec end
        end
        return specs
    end
    return api
end
return Probe
