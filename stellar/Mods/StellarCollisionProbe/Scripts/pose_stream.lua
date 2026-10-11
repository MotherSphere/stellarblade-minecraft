-- EVE pose stream: names EVE's skeletal mesh components for the native game-thread reader.
-- Game-thread only. Publishes alternating slots eve-pose-components.0/1 while Minecraft's request is fresh.
local Stream={}
local LEASE_MS=3000
local MAX_BONES=512 -- Per component, as the adapter and Minecraft parse it (suits carry up to 364 bones).
local CLOTH='SBPonytail' -- ponytail: only the long ponytail's cloth is streamed; add SBPonytailShort when someone plays with it.
local function identity(value) return type(value)=='string' and #value==32 and value:match('^[0-9a-f]+$')~=nil end
local function hex(address)
    assert(math.type(address)=='integer' and address>0 and address%8==0,'Invalid object address.')
    return string.format('%016x',address)
end
-- A look input scale the native adapter accepts: finite, magnitude 0.01 to 100.
local function scale(value)
    return type(value)=='number' and value==value and math.abs(value)>=0.01 and math.abs(value)<=100
end
local function token(value)
    return type(value)=='string' and #value>0 and value:match('^[!-~]+$')~=nil
end
local function shown(value) return value==nil and '?' or type(value)=='number' and string.format('%g',value) or tostring(value) end
function Stream.parse_request(text)
    if type(text)~='string' or #text>512 then return nil end
    local run,time,state=text:match('^SBMC_POSE_REQUEST 1\nRUN (%x+)\nTIME (%d+)\nSTATE (%a+)\nEND\n$')
    if not identity(run) or (state~='active' and state~='stopped') then return nil end
    return {run=run,time=tonumber(time),active=state=='active'}
end
-- timer: seconds for the step timing log (os.clock: wall time to the millisecond under the MSVC runtime).
function Stream.new(engine,directory,clock,log,link,timer)
    timer=timer or os.clock
    local api={}
    local run,generation,signature,published,binding,streamed
    local effects,effect_lines='',0 -- Last logged active effects; at most 400 log lines per Lua lifetime.
    local layout,assets,asset_seen=nil,{},{} -- Effect reading layout; effect assets in first-seen order (at most 32).
    local combat_logged=false -- EVE's combat-named functions and properties, logged once per Lua lifetime (at most 300 lines).
    local drone_state,drone_lines=nil,0 -- Last logged drone state; at most 200 log lines per Lua lifetime.
    local cloth_logged -- The run whose cloth component was logged.
    -- The reflection walks behind the table hold Stellar Blade's game thread (about 90 ms together for EVE and her
    -- drone, mostly the parameter arrays of her dynamic material instances, so a check every 200 ms cost a third of its
    -- frames). They run all at once for a new run or composition. Then a component's parameters are walked again only
    -- when its material key changes (one component a check), the known effects and the controller take turns, and the
    -- effect walks all run each check while they fit WALK_BUDGET_MS, else one holder a check for the next 100 checks.
    local WALK_BUDGET_MS=4
    local turn,composition,walks_rotate_until=0,nil,0
    local controller -- The last usable controller lookup.
    local lookups={} -- Component object -> its last material parameter lookup {ok=, items=, error=, key=, turn=}.
    local walked={} -- Component object, or 'pawn' for EVE's own effect components -> the labels its last walk saw.
    local timing,timing_lines={},0 -- Step durations since the last timing line; at most 300 lines per Lua lifetime.
    local STEPS={'tick','identities','controller','material keys','parameters','known effects','effects','table'}
    local function took(step,start)
        local ms=(timer()-start)*1000
        local s=timing[step] or {sum=0,max=0,count=0}
        s.sum,s.max,s.count=s.sum+ms,math.max(s.max,ms),s.count+1
        timing[step]=s
    end
    local function remember(found)
        for _,a in ipairs(found or {}) do
            if #assets<32 and not asset_seen[a.object] and token(a.name) and math.type(a.object)=='integer' and a.object%8==0 then
                asset_seen[a.object]=true
                assets[#assets+1]=a
            end
        end
    end
    local function read(name,bound)
        local f=io.open(directory..'/'..name,'rb')
        if not f then return nil end
        local text=f:read(bound+1); f:close()
        return text and #text<=bound and text or nil
    end
    -- Continue after a Lua reload: readers keep the higher generation of this run.
    local function recover(id)
        local best=0
        for slot=0,1 do
            local text=read('eve-pose-components.'..slot,65536)
            local r,g
            if text then r,g=text:match('^SBMC_POSE_COMPONENTS 1\nRUN (%x+)\nGENERATION (%d+)\n') end
            if r==id then best=math.max(best,tonumber(g)) end
        end
        return best
    end
    local function encode(list,place,controller,parameters)
        local lines={'SBMC_POSE_COMPONENTS 1','RUN '..run,'GENERATION '..generation,
            'SESSION '..(place and place.session or 'none'),
            place and string.format('ANCHOR %.17g %.17g %.17g %.17g',place.anchor.X,place.anchor.Y,place.anchor.Z,place.scale) or 'ANCHOR none',
            controller and string.format('CONTROLLER %s %s %08x %s %s %.9g %.9g',hex(controller.object),hex(controller.class),
                controller.offset,hex(controller.pawn),hex(controller.pawn_class),
                controller.yaw_scale,controller.pitch_scale) or 'CONTROLLER none',
            'COUNT '..#list}
        for index,c in ipairs(list) do
            lines[#lines+1]=string.format('COMPONENT %d %s %s %s %d %d %s %s %s',index,hex(c.object),hex(c.class),hex(c.mesh),
                #c.bones,#c.morphs,c.name,c.mesh_name,c.leader or '-')
            if #c.bones>0 then lines[#lines+1]='BONES '..index..' '..table.concat(c.bones,' ') end
            if #c.morphs>0 then lines[#lines+1]='MORPHS '..index..' '..table.concat(c.morphs,' ') end
        end
        lines[#lines+1]='PARAMETERS '..#parameters
        for index,q in ipairs(parameters) do
            lines[#lines+1]=string.format('PARAMETER %d %d %s %s %s %s %08x %d %d %d',index,q.component,q.slot,q.name,
                hex(q.object),hex(q.class),q.offset,q.element,q.index,q.floats)
        end
        if layout then
            lines[#lines+1]=string.format('EFFECTS %08x %08x %08x %08x %02x %s %s %d',layout.children,layout.asset,layout.template,
                layout.active_byte,layout.active_mask,hex(layout.niagara),hex(layout.particle),#assets)
            for index,a in ipairs(assets) do lines[#lines+1]=string.format('EFFECT %d %s %s',index,hex(a.object),a.name) end
        else lines[#lines+1]='EFFECTS none' end
        -- The component whose finished cloth the adapter streams after the payload.
        local cloth='none'
        for index,c in ipairs(list) do if c.cloth then cloth=tostring(index) end end
        lines[#lines+1]='CLOTH '..cloth
        lines[#lines+1]='END '..generation
        return table.concat(lines,'\n')..'\n'
    end
    local function write(text)
        local temporary=directory..'/eve-pose-components.tmp'
        local slot=directory..'/eve-pose-components.'..tostring(generation%2)
        local file,err=io.open(temporary,'wb')
        assert(file,err)
        local written,why=file:write(text)
        local closed,close_error=file:close()
        assert(written,why); assert(closed,close_error)
        -- MSVC rename refuses an existing destination; readers keep the other slot.
        local exists=io.open(slot,'rb')
        if exists then exists:close(); assert(os.remove(slot)) end
        assert(os.rename(temporary,slot))
    end
    local function check(request)
        if request.run~=run then run,generation,signature,composition=request.run,recover(request.run),nil,nil end
        turn=turn+1
        local list,skipped={},{}
        local started=timer()
        local identities,drone=engine.identities()
        took('identities',started)
        for _,c in ipairs(identities) do
            if not (token(c.name) and token(c.mesh_name) and (c.leader==nil or token(c.leader))) then
                skipped[#skipped+1]=tostring(c.name)
            elseif c.drone and #list>=16 then skipped[#skipped+1]=c.name..' (no room)' -- The drone follows EVE's own.
            else list[#list+1]=c; c.cloth=not c.drone and c.name==CLOTH or nil end
        end
        assert(#list>0 and #list<=16,'EVE needs one to sixteen drawable skeletal mesh components.')
        streamed=list
        if drone and drone~=drone_state and drone_lines<200 then
            drone_lines=drone_lines+1
            drone_state=drone
            log('EVE drone: '..drone)
        end
        local place=link()
        local keys={}
        for _,c in ipairs(list) do keys[#keys+1]=c.object..':'..c.mesh end
        -- A new run looks everything up; so does a new composition, except material parameters still valid. A table
        -- that fails to publish keeps what was looked up.
        local new_run=composition==nil
        local fresh=new_run or table.concat(keys,'|')~=composition
        composition=table.concat(keys,'|')
        -- This check's turns: a component's parameters (when it has no material key), then the known effects, then the
        -- controller; a component's attached effects, then EVE's own effect components (while the walks take turns).
        local lookup_turn,walk_turn=turn%(#list+2)+1,turn%(#list+1)+1
        if fresh or lookup_turn==#list+2 then
            started=timer()
            local found,value=pcall(engine.controller)
            controller=found and value and scale(value.yaw_scale) and scale(value.pitch_scale) and value or nil
            took('controller',started)
        end
        -- Material parameters are optional: a failing lookup publishes none rather than stopping the stream.
        local keys_of={} -- Listed entry -> its material key, when the engine gives one.
        if engine.material_key then
            started=timer()
            for _,c in ipairs(list) do
                local ok,key=pcall(engine.material_key,c)
                if ok and type(key)=='string' then keys_of[c]=key end
            end
            took('material keys',started)
        end
        local function look_up(c)
            started=timer()
            local ok,result=pcall(engine.parameters,{c})
            lookups[c.object]={ok=ok,items=ok and result or {},error=not ok and result or nil,key=keys_of[c],turn=turn}
            took('parameters',started)
        end
        if new_run then
            lookups={}
            for _,c in ipairs(list) do look_up(c) end
        elseif fresh then
            -- Components that stay keep their lookup while their key holds: the drone can come, go or be replaced
            -- without EVE's materials changing.
            local kept={}
            for _,c in ipairs(list) do kept[c.object]=lookups[c.object] end
            lookups=kept
            for _,c in ipairs(list) do
                local looked=lookups[c.object]
                if not (looked and looked.ok and keys_of[c] and looked.key==keys_of[c]) then look_up(c) end
            end
        else
            -- One lookup a check: the component whose key changed longest after its last lookup, else, on its turn, one
            -- without a key or whose last lookup failed.
            local due,since
            for _,c in ipairs(list) do
                local key,looked=keys_of[c],lookups[c.object]
                if key and (not looked or looked.key~=key) and (not due or (looked and looked.turn or 0)<since) then
                    due,since=c,looked and looked.turn or 0
                end
            end
            local turn_of=list[lookup_turn]
            if not due and turn_of and (not keys_of[turn_of] or not (lookups[turn_of.object] or {}).ok) then due=turn_of end
            if due then look_up(due) end
        end
        local parameters,failure={},nil
        for index,c in ipairs(list) do
            local looked=lookups[c.object]
            if looked and not looked.ok then failure=failure or looked.error end
            for _,q in ipairs(looked and looked.items or {}) do
                if #parameters<32 and token(q.slot) and token(q.name) and math.type(q.index)=='integer' and q.index>0
                    and q.element>=0 and q.element<1024 then
                    local copy={}
                    for k,v in pairs(q) do copy[k]=v end
                    copy.component=index -- Looked up alone, as component 1.
                    parameters[#parameters+1]=copy
                end
            end
        end
        -- Effects: the layout the adapter reads them with (found once), the assets Minecraft may draw (known ones once
        -- loaded, then any seen active on EVE), and a log line whenever the active set changes.
        if not layout and engine.effect_layout then
            local ok,found=pcall(engine.effect_layout)
            if ok and found.children>0 and found.children<0x4000 and found.children%8==0 and found.asset>0 and found.asset<0x4000
                and found.asset%8==0 and found.template>0 and found.template<0x4000 and found.template%8==0
                and found.active_byte>0 and found.active_byte<0x4000 and found.active_mask>0 and found.active_mask<256 then layout=found end
        end
        if engine.known_effects and (fresh or lookup_turn==#list+1) then
            started=timer()
            local ok,found=pcall(engine.known_effects)
            if ok then remember(found) end
            took('known effects',started)
        end
        if effects and engine.effects then
            local slots={}
            for _,c in ipairs(list) do slots[#slots+1]=c end
            slots[#slots+1]='pawn'
            local function slot_key(slot) return slot=='pawn' and 'pawn' or slot.object end
            local failed
            local function walk(slot)
                started=timer()
                local seen,active,found
                if slot=='pawn' then seen,active,found=pcall(engine.effects,{})
                else seen,active,found=pcall(engine.effects,{slot},true) end -- Its attached effects only.
                took('effects',started)
                if not seen then failed=failed or tostring(active); return end
                walked[slot_key(slot)]=active
                -- ponytail: the drone's effects are logged but take none of the 32 asset slots until they get sprite
                -- recipes; then raise the cap rather than reorder.
                if slot=='pawn' or not slot.drone then remember(found) end
            end
            if fresh or turn>=walks_rotate_until then
                walked={}
                local whole=timer()
                for _,slot in ipairs(slots) do if not failed then walk(slot) end end
                if (timer()-whole)*1000>WALK_BUDGET_MS then walks_rotate_until=turn+100 end
            else walk(slots[walk_turn]) end
            local now_effects
            if failed then now_effects='unavailable: '..failed
            else
                -- What each walk saw last: an effect that ended leaves the log once its holder is walked again.
                local union,seen={},{}
                for _,slot in ipairs(slots) do
                    for _,label in ipairs(walked[slot_key(slot)] or {}) do
                        if not seen[label] then seen[label]=true; union[#union+1]=label end
                    end
                end
                table.sort(union)
                now_effects=table.concat(union,' ')
            end
            if now_effects~=effects and effect_lines<400 then
                effect_lines=effect_lines+1
                log('EVE effects: '..(now_effects=='' and 'none' or now_effects))
                effects=not failed and now_effects or nil -- An unavailable lookup is logged once, then left alone.
            end
        end
        if not combat_logged and engine.combat_names then
            combat_logged=true
            local ok,names=pcall(engine.combat_names)
            if ok then
                for index=1,math.min(#names,300) do log('EVE combat: '..names[index]) end
                log(string.format('EVE combat: %d names%s',#names,#names>300 and ', first 300 logged' or ''))
            else log('EVE combat names unavailable: '..tostring(names)) end
        end
        -- Minecraft's avatar controls are permitted only for this run and the entered link.
        binding=place and {run=run,session=place.session} or nil
        local parts={place and string.format('%s %.17g %.17g %.17g %.17g',place.session,place.anchor.X,place.anchor.Y,place.anchor.Z,place.scale) or 'none',
            controller and string.format('%d:%d:%d:%d:%d:%.9g:%.9g',controller.object,controller.class,controller.offset,
                controller.pawn,controller.pawn_class,controller.yaw_scale,controller.pitch_scale) or 'no controller'}
        for _,c in ipairs(list) do
            parts[#parts+1]=table.concat({c.name,c.object,c.class,c.mesh,c.mesh_name,c.leader or '-'},':')
        end
        for _,q in ipairs(parameters) do
            parts[#parts+1]=table.concat({q.component,q.slot,q.name,q.object,q.class,q.offset,q.element,q.index,q.floats},':')
        end
        parts[#parts+1]=layout and string.format('effects:%d:%d:%d:%d:%d:%d:%d',layout.children,layout.asset,layout.template,
            layout.active_byte,layout.active_mask,layout.niagara,layout.particle) or 'no effects'
        for _,a in ipairs(assets) do parts[#parts+1]=a.name..':'..a.object end
        local next_signature=table.concat(parts,'|')
        if next_signature==signature then return true end
        started=timer()
        -- The drone never stops EVE's stream: a drone component the stream cannot carry, or past one slot of floats, is
        -- left out (the drone's come last), with its parameters.
        for i=#list,1,-1 do
            local c=list[i]
            c.bones,c.morphs=engine.names(c)
            local carried=#c.bones<=MAX_BONES and #c.morphs<=1024
            for _,name in ipairs(c.bones) do carried=carried and token(name) end
            for _,name in ipairs(c.morphs) do carried=carried and token(name) end
            if c.drone and not carried then skipped[#skipped+1]=table.remove(list,i).name..' (not streamable)'
            else
                for _,name in ipairs(c.bones) do assert(token(name),c.name..' has a bone name the stream cannot carry.') end
                for _,name in ipairs(c.morphs) do assert(token(name),c.name..' has a morph name the stream cannot carry.') end
                assert(#c.bones<=MAX_BONES and #c.morphs<=1024,c.name..' exceeds the pose stream bounds.')
            end
        end
        local function floats()
            local total=layout and 8*11 or 0
            for _,c in ipairs(list) do total=total+10+10*#c.bones+#c.morphs end
            for _,q in ipairs(parameters) do if q.component<=#list then total=total+q.floats end end
            return total
        end
        -- ponytail: with the cloth listed, the table keeps what a 65504-float slot leaves after 10 + 3 * 17309 cloth
        -- floats (the long ponytail's particles); a larger cloth needs bigger slots. Otherwise a 64 KiB slot's floats.
        local room=16352
        for _,c in ipairs(list) do if c.cloth then room=13567 end end
        while floats()>room and list[#list].drone do skipped[#skipped+1]=table.remove(list).name..' (no room)' end
        for i=#parameters,1,-1 do if parameters[i].component>#list then table.remove(parameters,i) end end
        assert(floats()<=room,'EVE pose exceeds one stream slot.')
        generation=generation+1
        local text=encode(list,place,controller,parameters)
        assert(#text<=65536,'EVE component table exceeds the stream bound.')
        write(text)
        took('table',started)
        -- Once per run: how Stellar Blade ticks, blends, resets and pauses the cloth, as found before world_render.lua's
        -- cut ('?': unreadable).
        for _,c in ipairs(list) do
            if c.cloth and cloth_logged~=run then
                cloth_logged=run
                local ok,s=pcall(function() return engine.cloth(c) end)
                log(ok and string.format('Cloth: %s tick option %s, blend weight %s, simulation disabled %s; teleport distance %s,'
                    ..' rotation %s, reset after teleport %s, max distance scale %s, wait for parallel task %s, update rate'
                    ..' optimizations %s, recently rendered %s, suspended %s, tick paused always %s',c.name,shown(s.tick),
                    shown(s.blend),shown(s.disabled),shown(s.teleport_distance),shown(s.teleport_rotation),shown(s.reset),
                    shown(s.scale),shown(s.wait),shown(s.uro),shown(s.rendered),shown(s.suspended),shown(s.paused))
                    or 'Cloth: '..c.name..' state unavailable: '..tostring(s))
            end
        end
        signature=next_signature
        published=generation
        return true,string.format('Pose stream generation %d: %d components%s%s%s%s.',generation,#list,
            place and ', anchored to the entered link' or ', no entered link',
            controller and '' or ', no controller rotation',
            #parameters>0 and string.format(', %d material parameters',#parameters)
                or (failure and ', material parameters unavailable: '..tostring(failure) or ''),
            #skipped>0 and '; skipped '..table.concat(skipped,', ') or '')
    end
    -- One line per 50 active checks: each step's mean and longest time in milliseconds, and how often it ran.
    local function report()
        if timing_lines<300 then
            timing_lines=timing_lines+1
            local parts={}
            for _,step in ipairs(STEPS) do
                local s=timing[step]
                parts[#parts+1]=s and string.format('%s %.1f/%.1f x%d',step,s.sum/s.count,s.max,s.count) or step..' -'
            end
            log('Pose stream timing (ms mean/max, runs): '..table.concat(parts,'; '))
        end
        timing={}
    end
    -- Call a few times per second. Returns true, or true plus a message when the published composition changes.
    function api.tick()
        local request=Stream.parse_request(read('eve-pose-request.txt',512))
        local now=clock()*1000
        if not request or not request.active or math.abs(now-request.time)>LEASE_MS then
            binding=nil
            timing={}
            if run then run,signature,published,composition=nil,nil,nil,nil; return true,'Pose stream idle.' end
            return true
        end
        local started=timer()
        local result=table.pack(check(request))
        took('tick',started)
        if timing.tick.count>=50 then report() end
        return table.unpack(result,1,result.n)
    end
    function api.generation() return published end
    function api.binding() return binding end
    -- The last check's meshes ({handle=, object=, ...}, EVE's then her drone's), for world_render.lua's pose guard.
    function api.components() return streamed or {} end
    return api
end
return Stream
