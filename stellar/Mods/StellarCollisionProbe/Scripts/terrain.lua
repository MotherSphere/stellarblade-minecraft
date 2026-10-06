-- Owns a bounded Minecraft collision patch and separate temporary lab guards.
-- The adapter performs every Unreal operation on the game thread.
local Terrain = {}

local function copy_box(box)
    return {box[1],box[2],box[3],box[4],box[5],box[6]}
end
local function equal_box(a,b)
    for i=1,6 do if a[i]~=b[i] then return false end end
    return true
end
local function copy_packet(packet)
    local boxes={}
    for i,box in ipairs(packet.boxes) do boxes[i]=copy_box(box) end
    return {session=packet.session,seq=packet.seq,dimension=packet.dimension,
        origin={x=packet.origin.x,y=packet.origin.y,z=packet.origin.z},
        bounds=copy_box(packet.bounds),boxes=boxes}
end
local function overlaps(spec,position,radius,half_height)
    -- Distance between the box and the capsule's vertical line segment.
    local c,e=spec.center,spec.extent
    local dx=math.max(math.abs(position.X-c.X)-e.X,0)
    local dy=math.max(math.abs(position.Y-c.Y)-e.Y,0)
    local dz=math.max(math.abs(position.Z-c.Z)-e.Z-(half_height-radius),0)
    return dx*dx+dy*dy+dz*dz < radius*radius-0.000001
end

function Terrain.new(engine,scale)
    scale=scale or 100
    assert(type(scale)=='number' and scale>0 and scale<math.huge,'Scale must be finite and positive.')
    local fixture
    local visuals_visible=true
    local api={}
    local function protected(fn)
        return function(...)
            local ok,value=pcall(fn,...)
            if not ok then return false,tostring(value) end
            return true,value
        end
    end
    local function same_context()
        local now=engine.snapshot()
        assert(now.pawn_id==fixture.origin.pawn_id and now.world_id==fixture.origin.world_id,
            'Player/world changed; the old return position and actors must not be reused.')
        return now
    end
    local function each_actor(fn)
        for _,group in ipairs({fixture.guards,fixture.terrain,fixture.pending}) do
            for _,item in ipairs(group) do fn(item) end
        end
    end
    local function valid_support()
        each_actor(function(item)
            assert(engine.valid(item.actor),'A terrain or guard actor is missing; return and clean up.')
        end)
    end
    local function spawn_owned(group,spec)
        spec.visuals_visible=visuals_visible
        local actor=engine.spawn(spec)
        assert(actor,'spawn returned no actor')
        group[#group+1]={actor=actor,spec=spec}
        engine.configure(actor,spec)
    end
    local function destroy_group(group)
        local remaining,errors={},{}
        for i=#group,1,-1 do
            local item=group[i]
            local ok,err=pcall(engine.destroy,item.actor)
            if not ok then
                remaining[#remaining+1]=item
                errors[#errors+1]=tostring(err)
            end
        end
        return remaining,table.concat(errors,'; ')
    end
    local function cleanup()
        local errors={}
        for _,key in ipairs({'pending','terrain','guards'}) do
            local remaining,err=destroy_group(fixture[key])
            fixture[key]=remaining
            if err~='' then errors[#errors+1]=err end
        end
        fixture.complete=false
        if #fixture.guards+#fixture.terrain+#fixture.pending==0 then fixture=nil end
        return #errors==0,table.concat(errors,'; ')
    end
    local function mapped(box,role)
        local a=fixture.anchor
        return {role=role,center={
            X=a.X+(box[1]+box[4])*scale/2,
            -- Swapping the up axes preserves the native camera's visible handedness.
            Y=a.Y+(box[3]+box[6])*scale/2,
            Z=a.Z+(box[2]+box[5])*scale/2},
            extent={X=(box[4]-box[1])*scale/2,Y=(box[6]-box[3])*scale/2,
                Z=(box[5]-box[2])*scale/2}}
    end
    local function lab_guards(bounds,half_height)
        local pad=25/scale
        local x0,y0,z0,x1,y1,z1=table.unpack(bounds)
        -- Keep feet within the exported Y bounds, including small native contact penetration.
        local bottom=y0+2/scale
        local top=y1+2*half_height/scale
        return {
            mapped({x0-pad,bottom-pad,z0-pad,x1+pad,bottom,z1+pad},'lab-floor'),
            mapped({x0-pad,bottom,z0-pad,x0,top,z1+pad},'lab-wall'),
            mapped({x1,bottom,z0-pad,x1+pad,top,z1+pad},'lab-wall'),
            mapped({x0,bottom,z0-pad,x1,top,z0},'lab-wall'),
            mapped({x0,bottom,z1,x1,top,z1+pad},'lab-wall'),
            mapped({x0-pad,top,z0-pad,x1+pad,top+pad,z1+pad},'lab-ceiling'),
        }
    end
    local function same_anchor(packet)
        local old=fixture.packet
        assert(packet.session==old.session and packet.dimension==old.dimension
            and packet.origin.x==old.origin.x and packet.origin.y==old.origin.y
            and packet.origin.z==old.origin.z and equal_box(packet.bounds,old.bounds),
            'Minecraft session, dimension or anchor changed; return before linking again.')
    end

    api.create=protected(function(packet)
        assert(not fixture,'A terrain patch already exists; return and clean up first.')
        visuals_visible=true
        local origin=engine.snapshot()
        assert(origin.radius>0 and origin.half_height>=origin.radius,'A valid EVE capsule is required.')
        local bounds=packet.bounds
        assert(bounds[1]*scale < -origin.radius and bounds[4]*scale > origin.radius
            and bounds[3]*scale < -origin.radius and bounds[6]*scale > origin.radius
            and bounds[2]<0 and bounds[5]>0,'The Minecraft anchor must fit inside the guarded patch.')
        local anchor={X=origin.position.X,Y=origin.position.Y,Z=origin.position.Z+600}
        assert(anchor.Z+bounds[2]*scale-50 > origin.position.Z+origin.half_height,
            'The bounded patch is too deep for the elevated lab; no actors created.')
        fixture={origin=origin,anchor=anchor,packet=copy_packet(packet),
            guards={},terrain={},pending={},entered=false,complete=false,
            entry={X=anchor.X,Y=anchor.Y,Z=anchor.Z+origin.half_height+5}}
        local ok,err=pcall(function()
            for _,spec in ipairs(lab_guards(bounds,origin.half_height)) do
                spawn_owned(fixture.guards,spec)
            end
            for _,box in ipairs(packet.boxes) do spawn_owned(fixture.terrain,mapped(box,'minecraft')) end
        end)
        if not ok then
            local cleaned,why=cleanup()
            error(tostring(err)..(cleaned and '; creation rolled back' or '; cleanup required: '..why))
        end
        fixture.complete=true
        return 'Minecraft collision patch ready. Entry is separate; lab guards are additional geometry.'
    end)

    api.enter=protected(function()
        assert(fixture,'Create the terrain patch first.')
        assert(fixture.complete,'Terrain incomplete; only return and cleanup are allowed.')
        local now=same_context()
        assert(not fixture.entered,'Already entered; native movement remains in control.')
        valid_support()
        each_actor(function(item)
            assert(not overlaps(item.spec,fixture.entry,now.radius,now.half_height),
                'The entry capsule intersects terrain; clear the Minecraft anchor before entering.')
        end)
        -- A native move can relocate the pawn and still report failure.
        fixture.entered=true
        assert(engine.move(fixture.entry,fixture.origin.rotation),
            'Entry teleport failed; supporting geometry retained. Return and clean up.')
        return 'Entered Minecraft collision patch once; native movement remains in control.'
    end)

    api.update=protected(function(packet)
        assert(fixture,'Create the terrain patch first.')
        assert(fixture.complete,'Terrain incomplete; only return and cleanup are allowed.')
        local now=same_context()
        same_anchor(packet)
        assert(packet.seq>=fixture.packet.seq,'Older terrain revision rejected.')
        if packet.seq==fixture.packet.seq then
            assert(#packet.boxes==#fixture.packet.boxes,'Geometry changed without a new revision.')
            for i,box in ipairs(packet.boxes) do
                assert(equal_box(box,fixture.packet.boxes[i]),'Geometry changed without a new revision.')
            end
            return 'Terrain revision unchanged.'
        end
        valid_support()
        local specs={}
        for _,box in ipairs(packet.boxes) do
            local spec=mapped(box,'minecraft')
            local unchanged=false
            for _,old in ipairs(fixture.packet.boxes) do
                if equal_box(box,old) then unchanged=true; break end
            end
            -- Existing contacts cannot make unrelated block edits fail.
            assert(unchanged or not overlaps(spec,now.position,now.radius,now.half_height),
                'Terrain update deferred: new geometry intersects EVE. Move away before retrying.')
            specs[#specs+1]=spec
        end
        local ok,err=pcall(function()
            for _,spec in ipairs(specs) do spawn_owned(fixture.pending,spec) end
        end)
        if not ok then
            local remaining,why=destroy_group(fixture.pending)
            fixture.pending=remaining
            fixture.complete=#remaining==0
            error(tostring(err)..(fixture.complete and '; previous terrain retained'
                or '; previous terrain retained; cleanup required: '..why))
        end
        -- The new floor is fully configured before any old collision actor is removed.
        local old_remaining,why=destroy_group(fixture.terrain)
        fixture.terrain=fixture.pending
        fixture.pending=old_remaining
        fixture.complete=#old_remaining==0
        assert(fixture.complete,'Replacement terrain retained; old actor cleanup required: '..why)
        fixture.packet=copy_packet(packet)
        return 'Minecraft collision revision updated.'
    end)

    api.restore=protected(function()
        assert(fixture,'No terrain patch is active.')
        same_context()
        if fixture.entered then
            assert(engine.move(fixture.origin.position,fixture.origin.rotation),
                'Return teleport failed; supporting terrain retained. Retry return.')
            fixture.entered=false
        end
        local ok,err=cleanup()
        assert(ok,'Some owned terrain actors remain; retry cleanup: '..err)
        return 'Returned and removed owned Minecraft collision and lab guard actors.'
    end)

    function api.status()
        if not fixture then return 'idle' end
        if not fixture.complete then return 'cleanup-required' end
        return fixture.entered and 'entered' or 'ready'
    end
    function api.set_visuals_visible(visible)
        assert(type(visible)=='boolean','Diagnostic visibility must be boolean.')
        visuals_visible=visible
        local failures={}
        if fixture then each_actor(function(item)
            local ok,why=pcall(engine.set_visual_visible,item.actor,visible)
            if not ok then failures[#failures+1]=tostring(why) end
        end) end
        if #failures>0 then return false,'Diagnostic visibility update failed: '..table.concat(failures,'; ') end
        return true,visible and 'Diagnostic meshes restored.' or 'Diagnostic meshes hidden; collision unchanged.'
    end
    function api.shapes()
        local specs={}
        if fixture then each_actor(function(item) specs[#specs+1]=item.spec end) end
        return specs
    end
    function api.pose()
        if not fixture then return nil end
        local ok,now=pcall(same_context)
        if not ok then return nil,tostring(now) end
        local a=fixture.anchor
        return {session=fixture.packet.session,ack=fixture.packet.seq,anchor={X=a.X,Y=a.Y,Z=a.Z},
            state=fixture.complete and (fixture.entered and 'entered' or 'ready') or 'idle',
            position={x=(now.position.X-a.X)/scale,
                y=(now.position.Z-now.half_height-a.Z)/scale,z=(now.position.Y-a.Y)/scale}}
    end
    return api
end
return Terrain
