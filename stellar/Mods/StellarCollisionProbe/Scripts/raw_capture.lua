-- Persistent native captures. Pixel ownership belongs to the engine image queue and raw frame bus.
local Capture=require('capture')
local Raw={}
local function read_header(directory,request)
    local path=directory..'/eve-frames-'..request.run..'.bin'
    local f=io.open(path,'rb')
    if not f then return nil end
    local bytes=f:seek('end');f:seek('set',0)
    local header=f:read(4096);f:close()
    assert(header and #header==4096 and header:sub(1,8)=='SBMCFRM1','Native frame bus has an invalid header.')
    local version,size,width,height,stride,total=string.unpack('<I4I4I4I4I4I4',header,9)
    local expected=((4096+request.width*request.height*8+63)//64)*64
    assert(version==1 and size==4096 and width==request.width and height==request.height
        and stride==expected and total==4096+3*stride and bytes==total,'Native frame bus dimensions differ from the request.')
    assert(header:sub(33,64)==request.run and header:sub(65,96)==request.session,'Native frame bus belongs to another session.')
    local middle=string.unpack('<I4',header,129)
    assert((middle&3)<3 and (middle&~7)==0,'Native frame bus has invalid slot ownership.')
    local pid,state,err=string.unpack('<I4I4I4',header,193)
    local beat,completed=string.unpack('<I8I8',header,209)
    local consumer=string.unpack('<I4',header,257)
    assert(consumer==1,'Minecraft stopped the native frame bus.')
    assert(state==0 or state==1,'Native raw producer stopped or failed (code '..err..').')
    return {ready=pid~=0 and state==1,beat=beat,completed=completed}
end
function Raw.new(engine,directory,clock)
    clock=clock or os.time
    local api,owned={},{}
    local request,context,pending,started,counter=nil,nil,nil,nil,0
    local enabled=false
    local warmup
    function api.busy()return enabled or #owned>0 end
    function api.cancel()enabled=false end
    function api.clear()
        enabled=false
        local errors={}
        for i=#owned,1,-1 do
            local handle=owned[i]
            if handle.actor then
                local ok,why=pcall(engine.destroy,handle.actor)
                if ok then handle.actor=nil else errors[#errors+1]=tostring(why) end
            end
            if not handle.actor and handle.target then
                local ok,why=pcall(engine.release,handle.target)
                if ok then handle.target=nil else errors[#errors+1]=tostring(why) end
            end
            if not handle.actor and not handle.target then table.remove(owned,i) end
        end
        if warmup then
            local ok,_,code=os.remove(warmup)
            if ok or code==2 then warmup=nil else errors[#errors+1]='warmup cleanup failed' end
        end
        if #errors>0 then return false,table.concat(errors,'; ') end
        request,context,pending=nil,nil,nil
        return true,'Native capture resources released.'
    end
    function api.start(value)
        if api.busy() then return false,'Native capture resources are still owned.' end
        assert(value.version==2 and value.state=='active','Raw capture requires a version2 active request.')
        request={version=2,run=value.run,session=value.session,width=value.width,height=value.height}
        enabled=true;started=clock();counter=0;pending=nil;context=nil
        return true
    end
    local function prepare(camera)
        for _,spec in ipairs({{source=2,format=2},{source=0,format=2},{source=4,format=6}}) do
            spec.width=request.width;spec.height=request.height;spec.persistent=true
            local handle={};owned[#owned+1]=handle
            handle.actor=assert(engine.spawn(camera))
            engine.configure(handle.actor,camera,spec)
            handle.target=assert(engine.create_target(camera,spec))
            engine.bind(handle.actor,handle.target)
            engine.capture(handle.actor)
        end
        -- One established synchronous depth fence initializes all three fresh resources.
        -- Later captures reuse them and submit only raw image-queue jobs.
        local name='eve-raw-warmup-'..request.run..'.hdr';warmup=directory..'/'..name
        local old=io.open(warmup,'rb');if old then old:close();error('Raw warmup path already exists.') end
        engine.export(owned[3].actor,owned[3].target,directory,name)
        local f=assert(io.open(warmup,'rb'),'Native capture warmup produced no depth data.')
        local header=f:read(1024);f:close()
        local h,w=header:match('\n%-Y (%d+) %+X (%d+)\r?\n')
        assert((header:match('^#%?RADIANCE\r?\n') or header:match('^#%?RGBE\r?\n'))
            and tonumber(w)==request.width and tonumber(h)==request.height,'Native capture warmup has invalid dimensions.')
        assert(os.remove(warmup));warmup=nil
    end
    local function tick(link)
        assert(enabled and request,'Native capture is stopped.')
        assert(link and link.session==request.session,'Native capture terrain session changed.')
        local now=clock()
        local header=read_header(directory,request)
        if not header or not header.ready then
            assert(now-started<6,'Native raw producer did not acknowledge the shared frame bus.')
            return true,'Waiting for native raw producer.'
        end
        assert(header.beat>0 and now*1000-header.beat<=3000 and header.beat-now*1000<=2000,'Native raw producer heartbeat expired.')
        if pending then
            if header.completed~=pending.counter then
                assert(header.completed<pending.counter and now-pending.time<5,'Native raw frame completion expired or changed.')
                return true
            end
            pending=nil
        end
        local camera=engine.camera()
        if context then
            assert(camera.pawn_id==context.pawn and camera.world_id==context.world,'Native capture pawn or world changed.')
        end
        counter=counter+1
        assert(counter<=9007199254740991,'Raw capture sequence exhausted.')
        local id=string.format('%d-%d',now,counter)
        local metadata=Capture.raw_metadata(id,now,camera,link,request.width,request.height,request.run)
        if #owned==0 then
            prepare(camera)
            context={pawn=camera.pawn_id,world=camera.world_id}
        else
            for _,handle in ipairs(owned) do engine.update(handle.actor,camera);engine.capture(handle.actor) end
        end
        -- Reserve before submission. A partial failure may still leave engine-owned tasks in flight.
        pending={counter=counter,time=now}
        Capture.publish_metadata(directory..'/eve-raw-frame.txt',metadata)
        local prefix='eve-raw-'..request.run..'-'..id
        engine.export_async(owned[1].target,directory,prefix..'-color.bmp',2)
        engine.export_async(owned[2].target,directory,prefix..'-mask.bmp',2)
        engine.export_async(owned[3].target,directory,prefix..'-depth.exr',3)
        return true,nil,true
    end
    function api.tick(link)
        local ok,result,message,submitted=pcall(tick,link)
        if not ok then enabled=false;return false,tostring(result) end
        return result,message,submitted
    end
    return api
end
return Raw
