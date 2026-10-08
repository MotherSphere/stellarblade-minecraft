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
local function box_key(b)
    return string.format('%.17g %.17g %.17g %.17g %.17g %.17g',b[1],b[2],b[3],b[4],b[5],b[6])
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
Terrain.overlaps=overlaps
-- A Minecraft box relative to the bridge origin ({minX,minY,minZ,maxX,maxY,maxZ}, blocks) as an Unreal box around anchor a.
function Terrain.mapped(box,a,scale,role)
    return {role=role,center={
        X=a.X+(box[1]+box[4])*scale/2,
        -- Swapping the up axes preserves the native camera's visible handedness.
        Y=a.Y+(box[3]+box[6])*scale/2,
        Z=a.Z+(box[2]+box[5])*scale/2},
        extent={X=(box[4]-box[1])*scale/2,Y=(box[6]-box[3])*scale/2,
            Z=(box[5]-box[2])*scale/2}}
end

-- Seconds of placing boxes per native frame while a revision imports (at least one box per frame).
local FRAME_BUDGET=0.004

-- clock: seconds for the frame budget and timings (os.clock, wall time under the MSVC runtime).
function Terrain.new(engine,scale,clock)
    scale=scale or 100
    clock=clock or os.clock
    assert(type(scale)=='number' and scale>0 and scale<math.huge,'Scale must be finite and positive.')
    -- fixture.terrain holds every box actor: the applied revision's and those placed so far for fixture.import, the
    -- newest revision being imported; fixture.guards hold EVE in the applied window, fixture.fresh in the imported one.
    -- fixture.pool holds parked actors (engine.park: no collision, hidden) that boxes and guards reuse before spawning,
    -- so a window move alternates two guard sets and recycles the boxes it retires.
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
        for _,group in ipairs({fixture.guards,fixture.fresh,fixture.terrain}) do
            for _,item in ipairs(group) do fn(item) end
        end
    end
    local function valid_support()
        each_actor(function(item)
            assert(engine.valid(item.actor),'A terrain or guard actor is missing; return and clean up.')
        end)
    end
    -- An import's summary: its slowest single actor operation and how many actors it reused or spawned.
    local function timed(started,count)
        local import=fixture.import
        if not import then return end
        import.slowest=math.max(import.slowest,clock()-started)
        if count then import[count]=import[count]+1 end
    end
    -- Callers check that spec stays clear of EVE: a parked actor takes collision only once configured in place.
    local function spawn_owned(group,spec,key)
        spec.visuals_visible=visuals_visible
        local started=clock()
        local parked=table.remove(fixture.pool)
        local actor=parked and parked.actor or engine.spawn(spec)
        assert(actor,'spawn returned no actor')
        group[#group+1]={actor=actor,spec=spec,key=key}
        engine.configure(actor,spec)
        timed(started,parked and 'reused' or 'spawned')
    end
    -- Destroys a group's actors, or parks them in the pool; what fails stays in the returned group.
    local function release(group,park)
        local remaining,errors={},{}
        for i=#group,1,-1 do
            local item,started=group[i],clock()
            local ok,err=pcall(park and engine.park or engine.destroy,item.actor)
            timed(started)
            if not ok then
                remaining[#remaining+1]=item
                errors[#errors+1]=tostring(err)
            elseif park then fixture.pool[#fixture.pool+1]={actor=item.actor} end
        end
        return remaining,table.concat(errors,'; ')
    end
    local function cleanup()
        local errors={}
        for _,key in ipairs({'pool','terrain','fresh','guards'}) do
            local remaining,err=release(fixture[key])
            fixture[key]=remaining
            if err~='' then errors[#errors+1]=err end
        end
        fixture.complete=false
        fixture.import=nil
        if #fixture.guards+#fixture.terrain+#fixture.fresh+#fixture.pool==0 then fixture=nil end
        return #errors==0,table.concat(errors,'; ')
    end
    local function mapped(box,role) return Terrain.mapped(box,fixture.anchor,scale,role) end
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
    -- The window (bounds) follows EVE; the session, dimension and anchor never move.
    local function same_anchor(packet)
        local old=fixture.packet
        assert(packet.session==old.session and packet.dimension==old.dimension
            and packet.origin.x==old.origin.x and packet.origin.y==old.origin.y
            and packet.origin.z==old.origin.z,
            'Minecraft session, dimension or anchor changed; return before linking again.')
    end
    -- An import that cannot finish: destroys what it placed (boxes outside the applied revision, the new window's
    -- guards) and keeps the applied terrain and guards.
    local function abandon(err)
        local applied,kept,spawned={},{},{}
        for _,box in ipairs(fixture.packet.boxes) do applied[box_key(box)]=true end
        for _,item in ipairs(fixture.terrain) do
            if applied[item.key] then kept[#kept+1]=item else spawned[#spawned+1]=item end
        end
        local remaining,why=release(spawned)
        local guards,guard_why=release(fixture.fresh)
        for _,item in ipairs(remaining) do kept[#kept+1]=item end
        fixture.terrain,fixture.fresh,fixture.import=kept,guards,nil
        fixture.complete=#remaining+#guards==0
        error(tostring(err)..(fixture.complete and '; previous terrain retained'
            or '; previous terrain retained; cleanup required: '..why..guard_why),0)
    end
    -- Places the importing revision's boxes for one frame budget; once all exist, parks the boxes it no longer has
    -- and, after a window move, the old window's guards. A message when it ends or waits, else nil.
    local function step()
        local import=fixture.import
        if not import then return nil end
        local now=same_context()
        local deadline=clock()+FRAME_BUDGET
        local waiting
        import.frames=import.frames+1
        local ok,err=pcall(function()
            repeat
                local new=import.queue[import.next]
                if not new then return end
                -- EVE may have walked into a box still queued since the revision arrived: it waits until she leaves.
                if overlaps(new.spec,now.position,now.radius,now.half_height) then waiting=true; return end
                spawn_owned(fixture.terrain,new.spec,new.key)
                import.next=import.next+1
            until clock()>=deadline
        end)
        if not ok then abandon(err) end
        if waiting then return 'Terrain import waiting: new geometry intersects EVE. Move away to complete it.' end
        if import.queue[import.next] then return nil end
        -- The new terrain and guards are complete before any old collision actor is removed.
        local kept,old={},{}
        for _,item in ipairs(fixture.terrain) do
            if import.wanted[item.key] then kept[#kept+1]=item else old[#old+1]=item end
        end
        local remaining,why=release(old,true)
        for _,item in ipairs(remaining) do kept[#kept+1]=item end
        fixture.terrain=kept
        local moved,guard_why=#fixture.fresh>0,''
        if moved then
            local guards
            guards,guard_why=release(fixture.guards,true)
            for _,item in ipairs(fixture.fresh) do guards[#guards+1]=item end
            fixture.guards,fixture.fresh=guards,{}
        end
        fixture.import=nil
        fixture.complete=#remaining==0 and guard_why==''
        assert(fixture.complete,'Replacement terrain retained; old actor cleanup required: '..why..guard_why)
        fixture.packet=import.packet
        return string.format('Minecraft collision revision %d: %d boxes added, %d removed, %d kept in %.0f ms over %d frames; reused %d, spawned %d, slowest op %.1f ms%s.',
            import.packet.seq,#import.queue,#old,#kept-#import.queue,(clock()-import.started)*1000,import.frames,
            import.reused,import.spawned,import.slowest*1000,moved and '; window moved' or '')
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
        -- The lab floor stays 4 m above EVE's starting centre however deep the patch reaches.
        local anchor={X=origin.position.X,Y=origin.position.Y,Z=origin.position.Z+400-bounds[2]*scale}
        assert(anchor.Z+bounds[2]*scale-50 > origin.position.Z+origin.half_height,
            'The bounded patch is too deep for the elevated lab; no actors created.')
        fixture={origin=origin,anchor=anchor,packet=copy_packet(packet),
            guards={},fresh={},terrain={},pool={},entered=false,complete=false,
            entry={X=anchor.X,Y=anchor.Y,Z=anchor.Z+origin.half_height+5}}
        local started=os.clock()
        local ok,err=pcall(function()
            for _,spec in ipairs(lab_guards(bounds,origin.half_height)) do
                spawn_owned(fixture.guards,spec)
            end
            for _,box in ipairs(packet.boxes) do spawn_owned(fixture.terrain,mapped(box,'minecraft'),box_key(box)) end
        end)
        if not ok then
            local cleaned,why=cleanup()
            error(tostring(err)..(cleaned and '; creation rolled back' or '; cleanup required: '..why))
        end
        fixture.complete=true
        return string.format('Minecraft collision patch ready: %d boxes in %.0f ms. Entry is separate; lab guards are additional geometry.',
            #packet.boxes,(os.clock()-started)*1000)
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
        local newest=fixture.import and fixture.import.packet or fixture.packet
        assert(packet.seq>=newest.seq,'Older terrain revision rejected.')
        if packet.seq==newest.seq then
            assert(#packet.boxes==#newest.boxes and equal_box(packet.bounds,newest.bounds),'Geometry changed without a new revision.')
            for i,box in ipairs(packet.boxes) do
                assert(equal_box(box,newest.boxes[i]),'Geometry changed without a new revision.')
            end
            if fixture.import then
                return string.format('Importing Minecraft collision revision %d: %d boxes left.',
                    packet.seq,#fixture.import.queue-fixture.import.next+1)
            end
            return 'Terrain revision unchanged.'
        end
        valid_support()
        -- A moved window gets its own guards before anything else; the applied window's guards stay until the new
        -- window is complete, so EVE never reaches terrain that is not imported yet.
        local moved=not equal_box(packet.bounds,fixture.packet.bounds)
        local guarded=moved and #fixture.fresh>0 and equal_box(packet.bounds,fixture.import.packet.bounds)
        local guards={}
        if moved and not guarded then
            local a,b=fixture.anchor,packet.bounds
            local x,y,z=(now.position.X-a.X)/scale,(now.position.Z-now.half_height-a.Z)/scale,(now.position.Y-a.Y)/scale
            assert(x>b[1] and x<b[4] and y>=b[2] and y<b[5] and z>b[3] and z<b[6],
                'Window move deferred: EVE is outside the new window.')
            guards=lab_guards(b,fixture.origin.half_height)
            for _,spec in ipairs(guards) do
                assert(not overlaps(spec,now.position,now.radius,now.half_height),'Window move deferred: a new guard intersects EVE.')
            end
        end
        -- Only changed boxes are rebuilt: an actor whose exact box remains, applied or already placed for an earlier
        -- import, keeps its collision.
        local present,queue,wanted={},{},{}
        for _,item in ipairs(fixture.terrain) do present[item.key]=true end
        for _,box in ipairs(packet.boxes) do
            local key=box_key(box)
            wanted[key]=true
            if not present[key] then
                local spec=mapped(box,'minecraft')
                -- Existing contacts cannot make unrelated block edits fail.
                assert(not overlaps(spec,now.position,now.radius,now.half_height),
                    'Terrain update deferred: new geometry intersects EVE. Move away before retrying.')
                queue[#queue+1]={spec=spec,key=key}
            end
        end
        if not guarded and #fixture.fresh>0 then
            -- A superseded window's guards; the applied window's guards still hold EVE.
            local remaining,why=release(fixture.fresh,true)
            fixture.fresh=remaining
            if #remaining>0 then fixture.complete=false; error('Superseded window guards remain; cleanup required: '..why,0) end
        end
        fixture.import={packet=copy_packet(packet),queue=queue,wanted=wanted,next=1,started=clock(),frames=0,
            reused=0,spawned=0,slowest=0}
        local ok,err=pcall(function()
            for _,spec in ipairs(guards) do spawn_owned(fixture.fresh,spec) end
        end)
        if not ok then abandon(err) end
        return step() or string.format('Importing Minecraft collision revision %d: %d boxes left.',
            packet.seq,#queue-fixture.import.next+1)
    end)

    api.step=protected(function()
        if not fixture or not fixture.import then return nil end
        assert(fixture.complete,'Terrain incomplete; only return and cleanup are allowed.')
        return step()
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
