-- One-shot metadata v1: seven ASCII lines, whitespace-separated decimal numbers.
-- SBMC_CAPTURE 1 <color|depth> <captureId> <unixSeconds>
-- SIZE <width> <height> <captureSource> <targetFormat>
-- CAMERA <x> <y> <z> <pitch> <yaw> <roll> <fovDegrees>
-- PAWN <x> <y> <z> <capsuleHalfHeight>
-- CONTEXT <pawnAddress> <worldAddress>
-- FILE <fixedImageBasename> <byteLength>
-- END <captureId>
-- Coordinates use Stellar Blade world units; color alpha is unverified inverse opacity.
-- Fixed filenames are for manual stills only; continuous capture needs paired frame slots.
local Capture = {}
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
        assert(data:sub(1,8)=='\137PNG\13\10\26\10' and data:sub(13,16)=='IHDR','Capture export is not a PNG.')
        local width,height=string.unpack('>I4I4',data,17)
        assert(width==mode.width and height==mode.height,'PNG dimensions differ from the capture.')
        assert(data:sub(-12)=='\0\0\0\0IEND\174\66\96\130','PNG export is incomplete.')
    else
        local header=data:sub(1,1024)
        assert(header:match('^#%?RADIANCE\r?\n') or header:match('^#%?RGBE\r?\n'),'Capture export is not Radiance HDR.')
        assert(header:find('FORMAT=32-bit_rle_rgbe',1,true),'Unsupported HDR encoding.')
        local height,width=header:match('\n%-Y (%d+) %+X (%d+)\r?\n')
        assert(tonumber(width)==mode.width and tonumber(height)==mode.height,'HDR dimensions differ from the capture.')
    end
    return #data
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
function Capture.new(engine,directory)
    assert(type(directory)=='string' and directory~='','Capture directory is required.')
    local api,owned={},{}
    local next_id=0
    function api.status() return (owned.actor or owned.target) and 'cleanup-required' or 'idle' end
    function api.cleanup()
        if owned.actor then
            local ok,why=pcall(engine.destroy,owned.actor)
            if not ok then return false,'Capture actor cleanup failed: '..tostring(why) end
            owned.actor=nil
        end
        -- A still-bound target stays owned until its capture actor has stopped rendering.
        if owned.target then
            local ok,why=pcall(engine.release,owned.target)
            if not ok then return false,'Capture target cleanup failed: '..tostring(why) end
            owned.target=nil
        end
        return true,'Capture resources released.'
    end
    function api.capture(kind)
        local mode=modes[kind]
        if not mode then return false,'Capture mode must be color or depth.' end
        if api.status()~='idle' then return false,'Capture cleanup is required before another still.' end
        local descriptor=directory..'/eve-capture-'..kind..'.txt'
        local temporary=descriptor..'.tmp'
        local result
        local ok,why=pcall(function()
            remove_existing(descriptor)
            remove_existing(temporary)
            remove_existing(directory..'/'..mode.file)
            local camera=engine.camera()
            check_camera(camera)
            next_id=next_id+1
            local epoch=os.time()
            owned.actor=assert(engine.spawn(camera),'Capture actor was not created.')
            engine.configure(owned.actor,camera,mode)
            owned.target=assert(engine.create_target(camera,mode),'Capture target was not created.')
            engine.bind(owned.actor,owned.target)
            engine.capture(owned.actor)
            engine.export(owned.actor,owned.target,directory,mode.file)
            local size=image_size(directory..'/'..mode.file,kind,mode)
            result=metadata(kind,next_id,epoch,mode,camera,size)
        end)
        local cleaned,cleanup_message=api.cleanup()
        if not ok then return false,tostring(why)..(cleaned and '' or '; '..cleanup_message) end
        if not cleaned then return false,cleanup_message end
        local published,publish_error=pcall(function()
            local file=assert(io.open(temporary,'wb'))
            local written,write_error=file:write(result)
            local closed,close_error=file:close()
            assert(written,write_error); assert(closed,close_error)
            assert(os.rename(temporary,descriptor))
        end)
        if not published then os.remove(temporary); return false,'Capture metadata publication failed: '..tostring(publish_error) end
        return true,'Exported '..mode.file..' and paired metadata; isolated pixels still require inspection.'
    end
    return api
end
return Capture
