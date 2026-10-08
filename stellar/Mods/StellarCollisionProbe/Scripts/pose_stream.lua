-- EVE pose stream: names EVE's skeletal mesh components for the native game-thread reader.
-- Game-thread only. Publishes alternating slots eve-pose-components.0/1 while Minecraft's request is fresh.
local Stream={}
local LEASE_MS=3000
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
function Stream.parse_request(text)
    if type(text)~='string' or #text>512 then return nil end
    local run,time,state=text:match('^SBMC_POSE_REQUEST 1\nRUN (%x+)\nTIME (%d+)\nSTATE (%a+)\nEND\n$')
    if not identity(run) or (state~='active' and state~='stopped') then return nil end
    return {run=run,time=tonumber(time),active=state=='active'}
end
function Stream.new(engine,directory,clock,log,link)
    local api={}
    local run,generation,signature,published,binding,streamed
    local effects,effect_lines='',0 -- Last logged active effects; at most 400 log lines per Lua lifetime.
    local layout,assets,asset_seen=nil,{},{} -- Effect reading layout; effect assets in first-seen order (at most 32).
    local combat_logged=false -- EVE's combat-named functions and properties, logged once per Lua lifetime (at most 300 lines).
    local drone_state,drone_lines=nil,0 -- Last logged drone state; at most 200 log lines per Lua lifetime.
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
    -- Call a few times per second. Returns true, or true plus a message when the published composition changes.
    function api.tick()
        local request=Stream.parse_request(read('eve-pose-request.txt',512))
        local now=clock()*1000
        if not request or not request.active or math.abs(now-request.time)>LEASE_MS then
            binding=nil
            if run then run,signature,published=nil,nil,nil; return true,'Pose stream idle.' end
            return true
        end
        if request.run~=run then run,generation,signature=request.run,recover(request.run),nil end
        local list,skipped={},{}
        local identities,drone=engine.identities()
        for _,c in ipairs(identities) do
            if not (token(c.name) and token(c.mesh_name) and (c.leader==nil or token(c.leader))) then
                skipped[#skipped+1]=tostring(c.name)
            elseif c.drone and #list>=16 then skipped[#skipped+1]=c.name..' (no room)' -- The drone follows EVE's own.
            else list[#list+1]=c end
        end
        assert(#list>0 and #list<=16,'EVE needs one to sixteen drawable skeletal mesh components.')
        streamed=list
        if drone and drone~=drone_state and drone_lines<200 then
            drone_lines=drone_lines+1
            drone_state=drone
            log('EVE drone: '..drone)
        end
        local place=link()
        local found,controller=pcall(engine.controller)
        if not found or not controller or not scale(controller.yaw_scale) or not scale(controller.pitch_scale) then controller=nil end
        -- Material parameters are optional: a failing lookup publishes none rather than stopping the stream.
        local read,result=pcall(engine.parameters,list)
        local parameters={}
        if read then
            for _,q in ipairs(result) do
                if #parameters<32 and token(q.slot) and token(q.name) and math.type(q.index)=='integer' and q.index>0
                    and q.element>=0 and q.element<1024 then parameters[#parameters+1]=q end
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
        if engine.known_effects then
            local ok,found=pcall(engine.known_effects)
            if ok then remember(found) end
        end
        if effects and engine.effects then
            local seen,active,found=pcall(engine.effects,list)
            -- ponytail: the drone's effects are logged but take none of the 32 asset slots until they get sprite
            -- recipes; then raise the cap rather than reorder.
            local eve={}
            for _,c in ipairs(list) do if not c.drone then eve[#eve+1]=c end end
            if seen and #eve<#list then
                local own,_,mine=pcall(engine.effects,eve)
                found=own and mine or {}
            end
            if seen then remember(found) end
            local now_effects=seen and table.concat(active,' ') or ('unavailable: '..tostring(active))
            if now_effects~=effects and effect_lines<400 then
                effect_lines=effect_lines+1
                log('EVE effects: '..(now_effects=='' and 'none' or now_effects))
                effects=seen and now_effects or nil -- An unavailable lookup is logged once, then left alone.
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
        -- The drone never stops EVE's stream: a drone component the stream cannot carry, or past one slot of floats, is
        -- left out (the drone's come last), with its parameters.
        for i=#list,1,-1 do
            local c=list[i]
            c.bones,c.morphs=engine.names(c)
            local carried=#c.bones<=256 and #c.morphs<=1024
            for _,name in ipairs(c.bones) do carried=carried and token(name) end
            for _,name in ipairs(c.morphs) do carried=carried and token(name) end
            if c.drone and not carried then skipped[#skipped+1]=table.remove(list,i).name..' (not streamable)'
            else
                for _,name in ipairs(c.bones) do assert(token(name),c.name..' has a bone name the stream cannot carry.') end
                for _,name in ipairs(c.morphs) do assert(token(name),c.name..' has a morph name the stream cannot carry.') end
                assert(#c.bones<=256 and #c.morphs<=1024,c.name..' exceeds the pose stream bounds.')
            end
        end
        local function floats()
            local total=layout and 8*11 or 0
            for _,c in ipairs(list) do total=total+10+10*#c.bones+#c.morphs end
            for _,q in ipairs(parameters) do if q.component<=#list then total=total+q.floats end end
            return total
        end
        while floats()>16352 and list[#list].drone do skipped[#skipped+1]=table.remove(list).name..' (no room)' end
        for i=#parameters,1,-1 do if parameters[i].component>#list then table.remove(parameters,i) end end
        assert(floats()<=16352,'EVE pose exceeds one stream slot.')
        generation=generation+1
        local text=encode(list,place,controller,parameters)
        assert(#text<=65536,'EVE component table exceeds the stream bound.')
        write(text)
        signature=next_signature
        published=generation
        return true,string.format('Pose stream generation %d: %d components%s%s%s%s.',generation,#list,
            place and ', anchored to the entered link' or ', no entered link',
            controller and '' or ', no controller rotation',
            #parameters>0 and string.format(', %d material parameters',#parameters) or (read and '' or ', material parameters unavailable: '..tostring(result)),
            #skipped>0 and '; skipped '..table.concat(skipped,', ') or '')
    end
    function api.generation() return published end
    function api.binding() return binding end
    -- The last check's meshes ({handle=, object=, ...}, EVE's then her drone's), for world_render.lua's pose guard.
    function api.components() return streamed or {} end
    return api
end
return Stream
