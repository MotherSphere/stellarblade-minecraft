-- One-shot metadata v1: seven ASCII lines, whitespace-separated decimal numbers.
-- SBMC_CAPTURE 1 <color|depth> <captureId> <unixSeconds>
-- SIZE <width> <height> <captureSource> <targetFormat>
-- CAMERA <x> <y> <z> <pitch> <yaw> <roll> <fovDegrees>
-- PAWN <x> <y> <z> <capsuleHalfHeight>
-- CONTEXT <pawnAddress> <worldAddress>
-- FILE <fixedImageBasename> <byteLength>
-- END <captureId>
-- Coordinates use Stellar Blade world units; color alpha is unverified inverse opacity.
-- Paired frame metadata v1/v2/v3: ten ASCII LF lines; v4 adds RUN before END for a continuous session.
-- V3 is one pending HD request: COLOR/MASK counts are zero; all images require observed file closes.
-- SBMC_FRAME <1|2|3|4> <unixSeconds-counter> <unixSeconds>
-- SIZE <320 180|1280 720> (HD is an explicit still profile only)
-- CAMERA <x> <y> <z> <pitch> <yaw> <roll> <horizontalFovDegrees>
-- PAWN <x> <y> <z> <capsuleHalfHeight>
-- CONTEXT <pawnAddress> <worldAddress>
-- LINK <none|32lowerHexSession> <terrainAck> <scale> <relativeFeetX> <relativeFeetY> <relativeFeetZ>
-- COLOR eve-frame-<id>-color.<png|bmp> <byteLength> (v2 uses BMP)
-- MASK eve-frame-<id>-mask.png <byteLength>
-- DEPTH eve-frame-<id>-depth.hdr <byteLength>
-- END <id>
-- Color is Source2/RGBA8, inverse-opacity mask Source0/RGBA8, depth Source4/RGBA16f.
-- A frame uses one camera snapshot in one game-thread call.
local Capture = {}
local frame_counter=0
local async_attempted=false
local modes = {
    color={width=320,height=180,source=0,format=2,file='eve-capture-color.png'},
    depth={width=320,height=180,source=4,format=6,file='eve-capture-depth.hdr'},
}
local function finite(value) return type(value)=='number' and value==value and math.abs(value)<1e15 end
local function check_camera(camera)
    for _,value in ipairs({camera.pawn_id,camera.world_id,camera.fov,camera.half_height,
        camera.position.X,camera.position.Y,camera.position.Z,
        camera.rotation.Pitch,camera.rotation.Yaw,camera.rotation.Roll,
        camera.pawn_position.X,camera.pawn_position.Y,camera.pawn_position.Z}) do
        assert(finite(value),'Capture camera contains a non-finite value.')
    end
    assert(camera.pawn_id>0 and camera.world_id>0 and camera.half_height>0
        and camera.fov>0 and camera.fov<180,'Capture camera or pawn is invalid.')
end
local function remove_existing(path)
    os.remove(path)
    local remaining=io.open(path,'rb')
    if remaining then remaining:close(); error('Could not remove previous capture: '..path) end
end
local function image_size(path,kind,mode)
    local file=assert(io.open(path,'rb'),'Native capture did not export a readable image.')
    local data=file:read(4*1024*1024+1); file:close()
    assert(data and #data>32 and #data<=4*1024*1024,'Capture image size is outside the diagnostic limit.')
    if kind=='color' then
        assert(data:sub(1,8)=='\137PNG\13\10\26\10','Capture export is not a PNG.')
        local offset,has_pixels,image_end=9,false,nil
        while offset+11<=#data do
            local length=string.unpack('>I4',data,offset)
            local chunk_end=offset+length+11
            assert(chunk_end<=#data,'PNG chunk is truncated.')
            local chunk=data:sub(offset+4,offset+7)
            if offset==9 then
                assert(chunk=='IHDR' and length==13,'PNG has no valid initial IHDR.')
                local width,height=string.unpack('>I4I4',data,offset+8)
                assert(width==mode.width and height==mode.height,'PNG dimensions differ from the capture.')
                assert(data:sub(offset+16,offset+20)=='\8\6\0\0\0','Capture PNG is not noninterlaced RGBA8.')
            elseif chunk=='IHDR' then
                error('PNG contains a duplicate IHDR.')
            elseif chunk=='IDAT' then
                has_pixels=has_pixels or length>0
            elseif chunk=='IEND' then
                assert(length==0 and data:sub(offset,chunk_end)=='\0\0\0\0IEND\174\66\96\130',
                    'PNG has no valid IEND marker.')
                assert(has_pixels,'PNG has no image data.')
                image_end=chunk_end
                break
            end
            offset=chunk_end+1
        end
        assert(image_end,'PNG export is incomplete.')
        if image_end<#data then
            -- The native exporter can append buffer padding. Publish only complete PNG chunks;
            -- never search for an IEND byte sequence inside compressed image data.
            data=data:sub(1,image_end)
            local normalized=assert(io.open(path,'wb'),'Could not normalize the native PNG export.')
            local written,write_error=normalized:write(data)
            local closed,close_error=normalized:close()
            assert(written,write_error); assert(closed,close_error)
        end
    else
        local header=data:sub(1,1024)
        assert(header:match('^#%?RADIANCE\r?\n') or header:match('^#%?RGBE\r?\n'),'Capture export is not Radiance HDR.')
        assert(header:find('FORMAT=32-bit_rle_rgbe',1,true),'Unsupported HDR encoding.')
        local height,width=header:match('\n%-Y (%d+) %+X (%d+)\r?\n')
        assert(tonumber(width)==mode.width and tonumber(height)==mode.height,'HDR dimensions differ from the capture.')
    end
    return #data
end
local function bmp_info(path,width,height,frame_color)
    local file=assert(io.open(path,'rb'),'Native BMP export is unavailable.')
    local data=file:read(4*1024*1024+1); file:close()
    assert(data and #data>=54 and #data<=4*1024*1024 and data:sub(1,2)=='BM','BMP size or signature is invalid.')
    local size,reserved1,reserved2,offset=string.unpack('<I4I2I2I4',data,3)
    local header,w,h,planes,bits,compression,pixel_size=string.unpack('<I4i4i4I2I2I4I4',data,15)
    assert(size==#data and reserved1==0 and reserved2==0,'BMP file header is invalid.')
    assert(header==40 or header==108 or header==124,'Unsupported BMP information header.')
    assert(w==width and math.abs(h)==height and planes==1,'BMP dimensions or plane count differ from the capture.')
    -- This color-only gate accepts ordinary uncompressed RGB, not palette/RLE or bitfield variants.
    assert((bits==24 or bits==32) and compression==0,
        string.format('Unsupported BMP format: %d bpp, compression %d.',bits,compression))
    local required=((w*bits+31)//32)*4*math.abs(h)
    assert(offset>=14+header and offset+required<=#data and (pixel_size==0 or pixel_size==required),
        'BMP pixel layout is incomplete or inconsistent.')
    if frame_color then
        local colors,important=string.unpack('<I4I4',data,47)
        assert(header==40 and bits==24 and h>0 and offset==54 and offset+required==#data
            and pixel_size==required and colors==0 and important==0,
            'Frame v2 requires the validated native 24-bit bottom-up BMP layout.')
    end
    return string.format('%dx%d, %d bpp, compression %d, %s, %d bytes',w,math.abs(h),bits,compression,
        h<0 and 'top-down' or 'bottom-up',#data),#data
end
local function metadata(kind,id,epoch,mode,camera,size)
    local p,r,e=camera.position,camera.rotation,camera.pawn_position
    return string.format('SBMC_CAPTURE 1 %s %d %d\nSIZE %d %d %d %d\n'
        ..'CAMERA %.9g %.9g %.9g %.9g %.9g %.9g %.9g\n'
        ..'PAWN %.9g %.9g %.9g %.9g\nCONTEXT %d %d\nFILE %s %d\nEND %d\n',
        kind,id,epoch,mode.width,mode.height,mode.source,mode.format,
        p.X,p.Y,p.Z,r.Pitch,r.Yaw,r.Roll,camera.fov,e.X,e.Y,e.Z,camera.half_height,
        camera.pawn_id,camera.world_id,mode.file,size,id)
end
local function write_file(path,data)
    local file=assert(io.open(path,'wb'))
    local written,write_error=file:write(data)
    local closed,close_error=file:close()
    assert(written,write_error); assert(closed,close_error)
end
local function publish(path,data)
    assert(#data<=2048,'Capture metadata exceeds its size limit.')
    local temporary=path..'.tmp'
    local previous
    local old=io.open(path,'rb')
    if old then previous=old:read(2049); old:close(); assert(#previous<=2048,'Previous capture metadata exceeds its size limit.') end
    local ok,why=pcall(function()
        remove_existing(temporary)
        write_file(temporary,data)
        remove_existing(path)
        local renamed,rename_error=os.rename(temporary,path)
        if not renamed then
            -- Windows rename cannot replace a destination. Restore the preceding descriptor
            -- if publication fails after removing it; readers reject incomplete descriptors.
            if previous then write_file(path,previous) end
            error(rename_error)
        end
    end)
    if not ok then os.remove(temporary); error(why) end
end
local function check_link(link)
    if not link then return 'LINK none 0 100 0 0 0\n' end
    assert(type(link)=='table' and type(link.session)=='string' and #link.session==32
        and link.session:match('^[0-9a-f]+$'),'Frame link has an invalid session.')
    assert(finite(link.ack) and link.ack%1==0 and link.ack>=1 and link.ack<=2147483647,'Frame link has an invalid terrain revision.')
    assert(finite(link.scale) and link.scale>0,'Frame link has an invalid scale.')
    local p=link.position
    assert(type(p)=='table' and finite(p.x) and finite(p.y) and finite(p.z),'Frame link has an invalid relative position.')
    return string.format('LINK %s %d %.9g %.9g %.9g %.9g\n',link.session,link.ack,link.scale,p.x,p.y,p.z)
end
local function frame_metadata(id,epoch,camera,link_line,layers,width,height,version,run)
    local p,r,e=camera.position,camera.rotation,camera.pawn_position
    return string.format('SBMC_FRAME %d %s %d\nSIZE %d %d\n'
        ..'CAMERA %.9g %.9g %.9g %.9g %.9g %.9g %.9g\n'
        ..'PAWN %.9g %.9g %.9g %.9g\nCONTEXT %d %d\n',
        version,id,epoch,width,height,p.X,p.Y,p.Z,r.Pitch,r.Yaw,r.Roll,camera.fov,e.X,e.Y,e.Z,camera.half_height,camera.pawn_id,camera.world_id)
        ..link_line..string.format('COLOR %s %d\nMASK %s %d\nDEPTH %s %d\n',
        layers[1].file,layers[1].size,layers[2].file,layers[2].size,layers[3].file,layers[3].size)
        ..(run and 'RUN '..run..'\n' or '')..'END '..id..'\n'
end
-- Raw transport keeps the same validated camera/link description as diagnostic images.
function Capture.raw_metadata(id,epoch,camera,link,width,height,run)
    check_camera(camera)
    assert(type(width)=='number' and width%1==0 and width>=1 and width<=3840
        and type(height)=='number' and height%1==0 and height>=1 and height<=2160,'Invalid raw capture dimensions.')
    assert(type(run)=='string' and #run==32 and run:match('^[0-9a-f]+$'),'Invalid raw capture run.')
    assert(link,'Raw capture requires a terrain link.')
    local bytes=width*height*4
    return frame_metadata(id,epoch,camera,check_link(link),{{file='raw',size=bytes},{file='none',size=0},
        {file='raw',size=bytes}},width,height,5,run)
end
Capture.publish_metadata=publish
-- Logger timestamps bracket each optional HD stage, including failed calls.
local function stage(trace,name,run)
    if not trace then return run() end
    trace(name..' start')
    local ok,result,detail=pcall(run)
    trace(name..' end'..((not ok or result==false) and ' failed' or ''))
    if not ok then error(result,0) end
    return result,detail
end
function Capture.new(engine,directory,log)
    assert(type(directory)=='string' and directory~='','Capture directory is required.')
    local api,owned,retired={},{},{}
    local next_id=0
    local previous_files={}
    local stream_enabled,pending_frame=false,nil
    local function purge_files()
        -- Invalidate the published descriptor before deleting its image generation.
        if retired['eve-frame.txt'] then
            local removed,_,code=os.remove(directory..'/eve-frame.txt')
            if removed or code==2 then retired['eve-frame.txt']=nil else return end
        end
        for name in pairs(retired) do
            local removed,_,code=os.remove(directory..'/'..name)
            if removed or code==2 then retired[name]=nil end
        end
    end
    local function retire_files(files)
        for _,layer in ipairs(files) do retired[layer.file]=true end
        purge_files()
    end
    local function frame_id(epoch)
        -- Also avoid identifiers left by a same-second Lua reload, regardless of color encoding.
        local id,available
        repeat
            frame_counter=frame_counter+1; id=string.format('%d-%d',epoch,frame_counter)
            available=true
            for _,suffix in ipairs({'color.png','color.bmp','mask.png','depth.hdr'}) do
                local file=io.open(directory..'/eve-frame-'..id..'-'..suffix,'rb')
                if file then file:close(); available=false end
            end
        until available
        return id
    end
    function api.stream_busy() return stream_enabled or pending_frame~=nil end
    function api.status()
        return (#owned>0 or next(retired)) and 'cleanup-required' or (api.stream_busy() and 'stream-owned' or 'idle')
    end
    function api.stream_enable(enabled)
        assert(type(enabled)=='boolean','Stream ownership requires a boolean.')
        if enabled and (api.status()~='idle' or async_attempted) then
            return false,'Another capture or retained manual async attempt owns capture resources.'
        end
        stream_enabled=enabled
        return true
    end
    function api.stream_pending()
        local p=pending_frame
        return p and {id=p.id,run=p.run,session=p.session,time=p.time,published=p.published,poisoned=p.poisoned} or nil
    end
    function api.cleanup()
        local failures={}
        for i=#owned,1,-1 do
            local handle=owned[i]
            if handle.actor then
                local ok,why=pcall(engine.destroy,handle.actor)
                if ok then handle.actor=nil else failures[#failures+1]='actor: '..tostring(why) end
            end
            -- A still-bound target stays owned until its capture actor has stopped rendering.
            if not handle.actor and handle.target then
                local ok,why=pcall(engine.release,handle.target)
                if ok then handle.target=nil else failures[#failures+1]='target: '..tostring(why) end
            end
            if not handle.actor and not handle.target then table.remove(owned,i) end
        end
        purge_files()
        if next(retired) then failures[#failures+1]='capture files could not be removed' end
        if #owned>0 or next(retired) then return false,'Capture cleanup failed: '..table.concat(failures,'; ') end
        return true,'Capture resources released.'
    end
    function api.clear()
        stream_enabled=false
        -- The draining reader still needs unresolved metadata, even after off/F11.
        if pending_frame then return api.cleanup() end
        retired['eve-frame.txt']=true
        retired['eve-frame.txt.tmp']=true
        for _,layer in ipairs(previous_files) do retired[layer.file]=true end
        previous_files={}
        purge_files()
        return api.cleanup()
    end
    local function allocate_capture(camera,mode,trace,label)
        local handle={}
        owned[#owned+1]=handle
        label=label or 'capture'
        stage(trace,label..' allocation',function()
            handle.actor=assert(engine.spawn(camera),'Capture actor was not created.')
            engine.configure(handle.actor,camera,mode)
            handle.target=assert(engine.create_target(camera,mode),'Capture target was not created.')
            engine.bind(handle.actor,handle.target)
        end)
        return handle
    end
    local function prepare_capture(camera,mode,trace,label)
        local handle=allocate_capture(camera,mode,trace,label)
        stage(trace,label..' capture',function() engine.capture(handle.actor) end)
        return handle
    end
    local function export_layer(handle,mode,kind,trace,label)
        stage(trace,label..' export',function()
            if kind=='bmp' then engine.export_bmp(handle.target,directory,mode.file)
            else engine.export(handle.actor,handle.target,directory,mode.file) end
        end)
        return stage(trace,label..' validation',function()
            if kind=='bmp' then
                local _,size=bmp_info(directory..'/'..mode.file,mode.width,mode.height,true)
                return size
            end
            return image_size(directory..'/'..mode.file,kind,mode)
        end)
    end
    local function capture_layer(camera,mode,kind,trace,label)
        label=label or kind
        return export_layer(prepare_capture(camera,mode,trace,label),mode,kind,trace,label)
    end
    function api.exportbmp()
        if api.status()~='idle' then return false,'Capture cleanup is required before the BMP comparison.' end
        local mode={width=1280,height=720,source=2,format=2,file='eve-exportbmp-kismet.png'}
        local bmp='eve-exportbmp.bmp'
        local trace=log and function(message) pcall(log,'BMP comparison '..message) end
        local result
        local ok,why=pcall(function()
            remove_existing(directory..'/'..mode.file); remove_existing(directory..'/'..bmp)
            local camera=engine.camera(); check_camera(camera)
            local handle=allocate_capture(camera,mode)
            local function png(label)
                remove_existing(directory..'/'..mode.file)
                engine.capture(handle.actor)
                stage(trace,label,function() engine.export(handle.actor,handle.target,directory,mode.file) end)
                image_size(directory..'/'..mode.file,'color',mode)
            end
            png('Kismet cold export')
            engine.capture(handle.actor)
            stage(trace,'BMP synchronous export',function() engine.export_bmp(handle.target,directory,bmp) end)
            result=bmp_info(directory..'/'..bmp,mode.width,mode.height)
            png('Kismet warm export')
        end)
        local cleaned,cleanup_message=api.cleanup()
        if not ok then return false,tostring(why)..(cleaned and '' or '; '..cleanup_message) end
        if not cleaned then return false,cleanup_message end
        return true,'Exported color-only BMP comparison: '..result..'. Diagnostic PNG/BMP retained; paired frame unchanged.'
    end
    local function async_layers(prefix)
        return {
            {width=1280,height=720,source=2,format=2,file=prefix..'-color.bmp',size=0},
            {width=1280,height=720,source=0,format=2,file=prefix..'-mask.png',size=0},
            {width=1280,height=720,source=4,format=6,file=prefix..'-depth.hdr'},
        }
    end
    local function submit_async(camera,layers,trace,pending)
        local prepared={}
        for i,layer in ipairs(layers) do
            prepared[i]=prepare_capture(camera,layer,trace,({'color','mask','depth'})[i])
        end
        -- Kismet's synchronous readback drains all earlier captures, so both ImageWriteQueue
        -- submissions see initialized TextureRHI resources even on a fresh generation.
        layers[3].size=export_layer(prepared[3],layers[3],'depth',trace,'depth')
        -- Manual diagnostics retain their one-shot output. Continuous frames instead require
        -- the exact consumer ACK before replacement; any submission exception stays unresolved.
        if not pending then async_attempted=true end
        stage(trace,'color BMP async submission',function()
            engine.export_async(prepared[1].target,directory,layers[1].file,2)
        end)
        stage(trace,'mask PNG async submission',function()
            engine.export_async(prepared[2].target,directory,layers[2].file,0)
        end)
    end
    function api.exportasync()
        if async_attempted then return false,'Async export was already attempted in this Lua lifetime; diagnostic files remain retained.' end
        if api.status()~='idle' then return false,'Capture cleanup is required before the async export test.' end
        local trace=log and function(message) pcall(log,'Async export test '..message) end
        local layers=async_layers('eve-exportasync')
        local ok,why=pcall(function()
            for _,layer in ipairs(layers) do remove_existing(directory..'/'..layer.file) end
            local camera=engine.camera(); check_camera(camera)
            submit_async(camera,layers,trace)
        end)
        -- In this verified game build, explicit target release queues resource destruction after
        -- the submitted readback on the same render queue. File writers own their CPU pixels.
        -- Do not validate, publish, remove, or retry diagnostic outputs after submission.
        local cleaned,cleanup_message=stage(trace,'cleanup',api.cleanup)
        if not ok then return false,tostring(why)..(cleaned and '' or '; '..cleanup_message) end
        if not cleaned then return false,cleanup_message end
        return true,'Async color/mask submission calls returned; writer completion is unverified. Diagnostic files retained; paired frame unchanged.'
    end
    function api.frameasync(link)
        if async_attempted then return false,'One async generation was already reserved or submitted; restart the game for another attempt.' end
        if api.status()~='idle' then return false,'Capture cleanup is required before an async frame.' end
        local result,id
        local trace=log and function(message) pcall(log,'Async frame '..(id or 'pending')..' '..message) end
        local ok,why=pcall(function()
            local link_line=check_link(link)
            local camera=engine.camera(); check_camera(camera)
            local epoch=os.time(); id=frame_id(epoch)
            local layers=async_layers('eve-frame-'..id)
            -- Reserve the sole generation before allocation. Even a depth/setup failure now needs
            -- a game restart, so retained files cannot accumulate through repeated attempts.
            async_attempted=true
            submit_async(camera,layers,trace)
            result=frame_metadata(id,epoch,camera,link_line,layers,1280,720,3)
        end)
        local cleaned,cleanup_message=stage(trace,'cleanup',api.cleanup)
        if not ok then return false,tostring(why)..(cleaned and '' or '; '..cleanup_message) end
        if not cleaned then return false,cleanup_message end
        local published,publish_error=pcall(stage,trace,'publication',function() publish(directory..'/eve-frame.txt',result) end)
        if not published then return false,'Pending frame publication failed: '..tostring(publish_error) end
        retire_files(previous_files)
        previous_files={}
        -- Async images never enter retirement: native writers may still hold their output paths.
        return true,'Published pending async EVE frame '..id..'; Minecraft must observe all image file closes before loading.'
    end
    function api.stream_retry()
        local p=pending_frame
        if not p then return true end
        if p.poisoned then return false,'Async submission is unresolved; retain this generation until the game closes.' end
        if p.published then return true end
        local cleaned,why=api.cleanup()
        if not cleaned then return false,why end
        local ok,err=pcall(publish,directory..'/eve-frame.txt',p.metadata)
        if not ok then return false,'Pending frame publication will retry: '..tostring(err) end
        p.published=true
        retire_files(previous_files); previous_files={}
        return true
    end
    function api.stream_ack(run,session,id)
        local p=pending_frame
        if not p or p.run~=run or p.session~=session or p.id~=id or not p.published or p.poisoned then return true end
        -- ACK proves the exact consumer finished file access; it is not a native task fence.
        retired['eve-frame.txt']=true
        for _,layer in ipairs(p.layers) do retired[layer.file]=true end
        local cleaned,why=api.cleanup()
        if not cleaned then return false,why end
        pending_frame=nil
        return true
    end
    function api.streamframe(link,run)
        if not stream_enabled or pending_frame or #owned>0 or next(retired) then
            return false,'Continuous capture is disabled or still owns a pending generation.'
        end
        local p
        local ok,why=pcall(function()
            assert(type(run)=='string' and #run==32 and run:match('^[0-9a-f]+$'),'Invalid capture run.')
            assert(link,'Continuous frames require an entered link.')
            local link_line=check_link(link)
            local camera=engine.camera(); check_camera(camera)
            local epoch=os.time(); local id=frame_id(epoch)
            p={id=id,run=run,session=link.session,time=epoch,layers=async_layers('eve-frame-'..id),published=false}
            pending_frame=p
            submit_async(camera,p.layers,nil,p)
            p.metadata=frame_metadata(id,epoch,camera,link_line,p.layers,1280,720,4,run)
        end)
        local cleaned,cleanup_message=api.cleanup()
        if not ok then
            if p then p.poisoned=true end
            return false,tostring(why)..(cleaned and '' or '; '..cleanup_message)
        end
        if not cleaned then return false,cleanup_message end
        local published,message=api.stream_retry()
        if not published then return false,message end
        return true
    end
    function api.capture(kind)
        local mode=modes[kind]
        if not mode then return false,'Capture mode must be color or depth.' end
        if api.status()~='idle' then return false,'Capture cleanup is required before another still.' end
        local descriptor=directory..'/eve-capture-'..kind..'.txt'
        local result
        local ok,why=pcall(function()
            remove_existing(descriptor)
            remove_existing(directory..'/'..mode.file)
            local camera=engine.camera()
            check_camera(camera)
            next_id=next_id+1
            local epoch=os.time()
            result=metadata(kind,next_id,epoch,mode,camera,capture_layer(camera,mode,kind))
        end)
        local cleaned,cleanup_message=api.cleanup()
        if not ok then return false,tostring(why)..(cleaned and '' or '; '..cleanup_message) end
        if not cleaned then return false,cleanup_message end
        local published,publish_error=pcall(publish,descriptor,result)
        if not published then return false,'Capture metadata publication failed: '..tostring(publish_error) end
        return true,'Exported '..mode.file..' and paired metadata; isolated pixels still require inspection.'
    end
    function api.frame(link,profile)
        if profile~=nil and profile~='hd' and profile~='hd-png' then return false,'Frame profile must be omitted, hd or hd-png.' end
        if api.status()~='idle' then return false,'Capture cleanup is required before another frame.' end
        local width,height=320,180
        if profile then width,height=1280,720 end
        local version=profile=='hd' and 2 or 1
        local layers={}
        local result,id
        local trace
        if profile and log then
            trace=function(message) pcall(log,'HD frame '..(id or 'pending')..' '..message) end
        end
        local ok,why=pcall(function()
            local link_line=check_link(link)
            local camera=engine.camera()
            check_camera(camera)
            local epoch=os.time()
            id=frame_id(epoch)
            local prepared={}
            local specs={{'color',2,2,version==2 and 'bmp' or 'png'},{'mask',0,2,'png'},{'depth',4,6,'hdr'}}
            for i,spec in ipairs(specs) do
                local layer={width=width,height=height,source=spec[2],format=spec[3],file='eve-frame-'..id..'-'..spec[1]..'.'..spec[4]}
                layers[#layers+1]=layer
                if version==2 then
                    prepared[i]=prepare_capture(camera,layer,trace,spec[1])
                else
                    layer.size=capture_layer(camera,layer,i==3 and 'depth' or 'color',trace,spec[1])
                end
            end
            if version==2 then
                -- ImageWriteQueue checks TextureRHI before synchronizing. Queue every capture
                -- first; the required Kismet readbacks finish earlier render commands before BMP.
                for _,i in ipairs({2,3,1}) do
                    local kind=i==1 and 'bmp' or (i==3 and 'depth' or 'color')
                    layers[i].size=export_layer(prepared[i],layers[i],kind,trace,specs[i][1])
                end
            end
            result=frame_metadata(id,epoch,camera,link_line,layers,width,height,version)
        end)
        local cleaned,cleanup_message=stage(trace,'cleanup',api.cleanup)
        if not ok or not cleaned then
            retire_files(layers)
            return false,(not ok and tostring(why)..'; ' or '')..(not cleaned and cleanup_message or 'Frame was not published.')
        end
        local published,publish_error=pcall(stage,trace,'publication',function() publish(directory..'/eve-frame.txt',result) end)
        if not published then retire_files(layers); return false,'Frame publication failed: '..tostring(publish_error) end
        retire_files(previous_files)
        previous_files=layers
        return true,'Exported paired EVE frame '..id..'; display color, inverse-opacity mask and depth require visual validation.'
    end
    return api
end
return Capture
