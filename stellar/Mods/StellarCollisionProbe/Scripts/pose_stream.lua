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
    local run,generation,signature,published,binding
    local effects,effect_lines='',0 -- Last logged active effects; at most 400 log lines per Lua lifetime.
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
        for _,c in ipairs(engine.identities()) do
            if not (token(c.name) and token(c.mesh_name) and (c.leader==nil or token(c.leader))) then
                skipped[#skipped+1]=tostring(c.name)
            else list[#list+1]=c end
        end
        assert(#list>0 and #list<=16,'EVE needs one to sixteen drawable skeletal mesh components.')
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
        -- Diagnostic: log the set of EVE's active particle effects whenever it changes.
        if effects and engine.effects then
            local seen,active=pcall(engine.effects,list)
            local now_effects=seen and table.concat(active,' ') or ('unavailable: '..tostring(active))
            if now_effects~=effects and effect_lines<400 then
                effect_lines=effect_lines+1
                log('EVE effects: '..(now_effects=='' and 'none' or now_effects))
                effects=seen and now_effects or nil -- An unavailable lookup is logged once, then left alone.
            end
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
        local next_signature=table.concat(parts,'|')
        if next_signature==signature then return true end
        for _,c in ipairs(list) do
            c.bones,c.morphs=engine.names(c)
            for _,name in ipairs(c.bones) do assert(token(name),c.name..' has a bone name the stream cannot carry.') end
            for _,name in ipairs(c.morphs) do assert(token(name),c.name..' has a morph name the stream cannot carry.') end
            assert(#c.bones<=256 and #c.morphs<=1024,c.name..' exceeds the pose stream bounds.')
        end
        local floats=0
        for _,c in ipairs(list) do floats=floats+10+10*#c.bones+#c.morphs end
        for _,q in ipairs(parameters) do floats=floats+q.floats end
        assert(floats<=16352,'EVE pose exceeds one stream slot.')
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
    return api
end
return Stream
