-- Minecraft's mobs as walls for EVE (eve-mobs.txt, SBMC_MOBS 1): each listed box gets an invisible box actor in the lab
-- that blocks only EVE's own capsule channel, so she stops at a mob as at a block while cameras, traces and attacks pass
-- through it; it has no mesh, AI or damage and is never a lock-on target. A box that would land inside EVE waits without
-- collision until she has left it, as terrain updates wait, so Stellar Blade never shoves her out of it. A newer SEQ of
-- the entered link's session (a first one only within 2.5 seconds of TIME) holds for LEASE seconds; staleness, link loss,
-- F11 or an error destroys every box, retried every frame until done; spawn and placement errors together stop new boxes
-- after TRIES. Game-thread only; call tick each native frame.
local Terrain=require('terrain')
local Mobs={}
local FRESH_MS,LEASE,MAX,TRIES=2500,0.6,8,3
function Mobs.parse(text)
    if type(text)~='string' or #text>1024 then return nil end
    local session,seq,time,count,body,ending=text:match('^SBMC_MOBS 1\nSESSION (%x+)\nSEQ (%d+)\nTIME (%d+)\nCOUNT (%d)\n(.-)END (%d+)\n$')
    if not session or #session~=32 or session:find('[^0-9a-f]') or seq~=ending or tonumber(count)>MAX then return nil end
    local boxes={}
    for line in body:gmatch('([^\n]*)\n') do
        local box={line:match('^MOB (%S+) (%S+) (%S+) (%S+) (%S+) (%S+)$')}
        if #box~=6 then return nil end
        for i=1,6 do
            box[i]=tonumber(box[i])
            if not box[i] or box[i]~=box[i] or math.abs(box[i])>32 then return nil end
        end
        if box[1]>=box[4] or box[2]>=box[5] or box[3]>=box[6] then return nil end
        boxes[#boxes+1]=box
    end
    if #boxes~=tonumber(count) then return nil end
    return {session=session,seq=tonumber(seq),time=tonumber(time),boxes=boxes}
end
-- engine: blocker_spawn() (an actor), blocker_configure(actor) (a line), blocker_place(actor,spec|nil,solid),
-- blocker_destroy(actor) and snapshot() (EVE's capsule, and whether she passes through characters).
-- wall: Unix seconds; real: monotonic seconds; link: the entered link ({session=,anchor=,scale=}) or nil.
function Mobs.new(engine,directory,wall,real,log,link)
    local api,lines,failures,stuck={},0,0,false
    local ignoring -- Whether EVE passed through characters at the last snapshot (no wall is solid then).
    local last,seen -- The newest accepted publication, and when its sequence arrived.
    local walls={} -- {actor=,key=} per listed box, in list order; key is what was last placed.
    local function note(message) if lines<200 then lines=lines+1; log('Mobs: '..message) end end
    local function read()
        local f=io.open(directory..'/eve-mobs.txt','rb')
        if not f then return nil end
        local text=f:read(1025); f:close()
        return text
    end
    function api.release(why)
        if #walls==0 then return true,'Mob walls idle.' end
        local kept,errors={},{}
        for _,item in ipairs(walls) do
            local ok,err=pcall(engine.blocker_destroy,item.actor)
            if not ok then kept[#kept+1]=item; errors[#errors+1]=tostring(err) end
        end
        local removed=#walls-#kept
        walls=kept
        if #kept>0 then
            if not stuck then stuck=true; note('release failed, retrying: '..table.concat(errors,'; ')) end
            return false,'Mob walls not removed yet: '..table.concat(errors,'; ')
        end
        stuck=false
        note(string.format('released %d (%s)',removed,why))
        return true,'Mob walls removed.'
    end
    local function place(item,spec,solid)
        local key=spec and string.format('%.1f %.1f %.1f %.1f %.1f %.1f %s',spec.center.X,spec.center.Y,spec.center.Z,
            spec.extent.X,spec.extent.Y,spec.extent.Z,tostring(solid)) or 'parked'
        if key==item.key then return end
        engine.blocker_place(item.actor,spec,solid)
        item.key=key
    end
    function api.tick()
        if stuck and not api.release('retry') then return end
        local here,now=link(),real()
        local event=here and Mobs.parse(read())
        if event and event.session==here.session then
            local same=last and last.session==event.session
            if same and event.seq>last.seq or not same and math.abs(wall()*1000-event.time)<=FRESH_MS then last,seen=event,now end
        end
        local boxes=here and last and last.session==here.session and now-seen<=LEASE and last.boxes
        if not boxes then
            if #walls>0 then api.release(not here and 'link lost' or 'stale') end
            return
        end
        local ok,why=pcall(function()
            local eve
            for i,box in ipairs(boxes) do
                if not walls[i] then
                    if failures>=TRIES then break end
                    walls[i]={actor=engine.blocker_spawn()} -- Owned before its setup can fail.
                    local info=engine.blocker_configure(walls[i].actor)
                    if i==1 then note('first wall spawned, '..tostring(info)) end
                end
                local spec=Terrain.mapped(box,here.anchor,here.scale,'mob')
                if not eve then
                    eve=engine.snapshot()
                    if eve.ignoring~=ignoring then
                        ignoring=eve.ignoring
                        note(ignoring and 'EVE passes through characters: walls open' or 'EVE meets characters: walls solid')
                    end
                end
                place(walls[i],spec,not eve.ignoring and not Terrain.overlaps(spec,eve.position,eve.radius,eve.half_height))
            end
            for i=#boxes+1,#walls do place(walls[i],nil,false) end
        end)
        if not ok then
            failures=failures+1
            note(string.format('error (%d/%d): %s',failures,TRIES,tostring(why)))
            api.release('error')
        end
    end
    return api
end
return Mobs
