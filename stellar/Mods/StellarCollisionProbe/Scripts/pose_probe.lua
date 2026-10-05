-- Bounded pose diagnostic for the avatar bridge. Game-thread only; writes two private files when done.
local Probe={}
local MAX_FRAMES,MAX_SECONDS=900,15
local function transform_floats(t)
    return t.qx,t.qy,t.qz,t.qw,t.x,t.y,t.z,t.sx,t.sy,t.sz
end
function Probe.new(engine,directory,clock,log)
    local api={}
    local active,components,frames,started,chunks,timing
    local function finish(reason)
        active=false
        local lines={'SBMC_POSE_PROBE 1','REASON '..reason,string.format('FRAMES %d',frames)}
        for index,c in ipairs(components) do
            lines[#lines+1]=string.format('COMPONENT %d %s mesh=%s bones=%d morphs=%d weights=%d leader=%s parent=%s socket=%s recorded=%s method=%s',
                index,c.name,c.mesh,c.bones,#c.morphs,c.weights or 0,c.leader or '-',c.parent or '-',c.socket or '-',tostring(c.recorded),c.method or '-')
            if c.fallback then lines[#lines+1]='FALLBACK '..index..' '..c.fallback:gsub('\n',' ') end
            if c.recorded and c.names then lines[#lines+1]='BONES '..index..' '..table.concat(c.names,' ') end
            if #c.morphs>0 then lines[#lines+1]='MORPHS '..index..' '..table.concat(c.morphs,' ') end
        end
        for name,value in pairs(timing) do
            if value.count>0 then
                lines[#lines+1]=string.format('TIMING %s count=%d mean_ms=%.4f max_ms=%.3f',name,value.count,value.total*1000/value.count,value.max*1000)
            end
        end
        lines[#lines+1]='END'
        local text=io.open(directory..'/eve-pose-probe.txt','wb')
        assert(text,'Pose probe summary cannot be written.')
        text:write(table.concat(lines,'\n')..'\n'); text:close()
        local data=io.open(directory..'/eve-pose-probe.bin','wb')
        assert(data,'Pose probe recording cannot be written.')
        data:write(table.concat(chunks)); data:close()
        return string.format('Pose probe finished (%s): %d frames, %d components.',reason,frames,#components)
    end
    local function measure(name,run)
        local before=clock()
        local result=run()
        local elapsed=clock()-before
        local value=timing[name] or {count=0,total=0,max=0}
        value.count=value.count+1; value.total=value.total+elapsed; value.max=math.max(value.max,elapsed)
        timing[name]=value
        return result
    end
    function api.active() return active end
    function api.start()
        if active then return false,'Pose probe is already recording.' end
        components,frames,started,chunks,timing=engine.components(),0,clock(),{},{}
        assert(#components>0,'EVE has no skeletal mesh components.')
        for _,c in ipairs(components) do c.recorded=not c.leader; c.morphs=c.morphs or {} end
        active=true
        return true,string.format('Pose probe recording up to %d frames or %d seconds; %d components.',MAX_FRAMES,MAX_SECONDS,#components)
    end
    function api.tick(frame)
        if not active then return true end
        local ran,result=pcall(function()
            local now=clock()
            if frames>=MAX_FRAMES or now-started>=MAX_SECONDS then return finish('limit') end
            local parts={string.pack('<I4d',frame,now-started)}
            local snapshots=measure('frame_total',function()
                local list={}
                for index,c in ipairs(components) do
                    if c.recorded then list[index]=measure('snapshot_'..c.name,function() return engine.snapshot(c.handle) end) end
                end
                return list
            end)
            for index,c in ipairs(components) do
                parts[#parts+1]=string.pack('<'..string.rep('f',10),transform_floats(engine.world(c.handle)))
                local snapshot=snapshots[index]
                if snapshot then
                    if not c.names then c.names,c.bones,c.method,c.fallback=snapshot.names,#snapshot.names,snapshot.method,snapshot.fallback end
                    assert(#snapshot.transforms==c.bones,'Bone count changed during the probe.')
                    for _,t in ipairs(snapshot.transforms) do parts[#parts+1]=string.pack('<'..string.rep('f',10),transform_floats(t)) end
                    -- Weights follow the mesh's MorphTargets order; their count is fixed by the first frame.
                    c.weights=c.weights or #snapshot.weights
                    assert(#snapshot.weights==c.weights,'Morph weight count changed during the probe.')
                    for _,weight in ipairs(snapshot.weights) do parts[#parts+1]=string.pack('<f',weight) end
                end
            end
            chunks[#chunks+1]=table.concat(parts)
            frames=frames+1
            return nil
        end)
        if not ran then
            local ok,message=pcall(finish,'error: '..tostring(result))
            return false,ok and message or tostring(message)
        end
        if result then return true,result end
        return true
    end
    return api
end
return Probe
