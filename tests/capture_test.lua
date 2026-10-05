-- Native Unreal calls are substituted; filesystem publication and ownership are real.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Capture=pcall(require,'capture')
assert(loaded,'The one-shot capture lifecycle module is missing')
local directory=os.tmpname(); os.remove(directory)
assert(os.execute('mkdir '..directory))
local function write(path,data) local f=assert(io.open(path,'wb')); assert(f:write(data)); assert(f:close()) end
local function read(path) local f=io.open(path,'rb'); if not f then return nil end; local s=f:read('*a'); f:close(); return s end
-- A valid compressed RGBA PNG; its IDAT intentionally contains IEND-like bytes.
local png_hex='89504e470d0a1a0a0000000d4948445200000140000000b40806000000e5e4f5110000010c494441547801001100eeff0000'
    ..'00000049454e44ae42608200000000edc1010d000000c2a0f74f6d0e37a00000000000000000000000000000000000000000'
    ..'0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000'
    ..'0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000'
    ..'0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000'
    ..'0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000'
    ..'0000003831e04502f32811669f0000000049454e44ae426082'
local png=png_hex:gsub('..',function(byte)return string.char(tonumber(byte,16))end)
local hdr='#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 180 +X 320\n'..string.rep('\2\2\1\64'..string.rep('\255\0\255\0\194\0',4),180)
-- Complete transparent RGBA HD PNG and zero-valued RGBE HD image.
local function unhex(value) return value:gsub('..',function(byte)return string.char(tonumber(byte,16))end) end
local png_hd=unhex('89504e470d0a1a0a0000000d4948445200000500000002d00806000000cf7ddd5600000e0c4944415478daedc18100000000c3a0f9535fe1005501')
    ..string.rep('\0',3572)..unhex('af0146180001850592430000000049454e44ae426082')
local hdr_hd='#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 720 +X 1280\n'
    ..string.rep('\2\2\5\0'..string.rep(string.rep('\255\0',10)..'\138\0',4),720)
local function bmp_hd(bits)
    local bytes=1280*720*bits//8
    return 'BM'..string.pack('<I4I2I2I4I4i4i4I2I2I4I4i4i4I4I4',54+bytes,0,0,54,40,1280,720,1,bits,0,bytes,0,0,0,0)
        ..string.rep('\0',bytes)
end
local function backend()
    local b={actors=0,targets=0,spawned=0,camera_reads=0,sources={},cameras={},exports=0,files={}}
    function b.camera() b.camera_reads=b.camera_reads+1; return {pawn_id=11,world_id=12,position={X=1,Y=2,Z=3},rotation={Pitch=4,Yaw=5,Roll=6},fov=70,pawn_position={X=7,Y=8,Z=9},half_height=95} end
    function b.spawn(camera) b.cameras[#b.cameras+1]=camera; b.actors=b.actors+1; b.spawned=b.spawned+1; return {} end
    function b.configure(_,_,mode) b.sources[#b.sources+1]=mode.source; if b.fail_configure then error('configure failed') end end
    function b.create_target(_,mode) b.targets=b.targets+1; return {mode=mode} end
    function b.bind() if b.fail_bind then error('bind failed') end end
    function b.capture() b.captures=(b.captures or 0)+1; if b.fail_capture then error('capture failed') end end
    function b.export(_,target,dir,name)
        b.png_target=target
        b.exports=b.exports+1; b.files[#b.files+1]=name
        if b.no_export or b.fail_export_at==b.exports then return end
        local depth=name:match('%.hdr$')
        local image=target.mode.width==1280 and (depth and hdr_hd or png_hd) or (depth and hdr or png)
        write(dir..'/'..name,b.image or (b.bad_export and 'wrong format' or image))
    end
    function b.export_bmp(target,dir,name)
        assert(target.mode.width==1280 and target.mode.height==720 and target.mode.source==2)
        if name=='eve-exportbmp.bmp' then assert(target==b.png_target) end
        b.bmp_exports=(b.bmp_exports or 0)+1
        b.files[#b.files+1]=name
        if b.fail_bmp then error('BMP export failed') end
        write(dir..'/'..name,b.bmp_image or bmp_hd(24))
    end
    function b.destroy() if b.fail_destroy then error('destroy failed') end; b.actors=b.actors-1 end
    function b.release() if b.fail_release then error('release failed') end; b.targets=b.targets-1 end
    return b
end
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
check('new color export publishes paired metadata after complete cleanup',function()
    local b=backend(); local c=Capture.new(b,directory)
    assert(c.capture('color')); assert(c.status()=='idle'); assert(b.actors==0 and b.targets==0)
    local meta=assert(read(directory..'/eve-capture-color.txt'))
    assert(meta:match('^SBMC_CAPTURE 1 color 1 %d+\nSIZE 320 180 0 2\n'))
    assert(meta:match('\nCONTEXT 11 12\nFILE eve%-capture%-color.png %d+\nEND 1\n$'))
end)
check('native trailing PNG padding is stripped at the actual IEND chunk',function()
    local b=backend(); b.image=png..string.rep('\0',2049); local c=Capture.new(b,directory)
    assert(c.capture('color'),'A complete PNG with native trailing padding must be accepted')
    assert(read(directory..'/eve-capture-color.png')==png,'Only bytes after the real IEND may be stripped')
    local size=read(directory..'/eve-capture-color.txt'):match('FILE eve%-capture%-color.png (%d+)')
    assert(tonumber(size)==#png,'Metadata must describe the normalized file')
end)
check('truncated PNG chunks, missing IDAT, and corrupt IEND are rejected',function()
    local cases={png:sub(1,-14),png:sub(1,-2),png:sub(1,33)..png:sub(-12),png:sub(1,-2)..'x'}
    for _,invalid in ipairs(cases) do
        local b=backend(); b.image=invalid; local c=Capture.new(b,directory)
        assert(not c.capture('color')); assert(not read(directory..'/eve-capture-color.txt'))
        assert(b.actors==0 and b.targets==0)
    end
end)
check('depth output has its own HDR file, source and format metadata',function()
    local b=backend(); local c=Capture.new(b,directory)
    assert(c.capture('depth')); assert(b.actors==0 and b.targets==0)
    assert(read(directory..'/eve-capture-depth.txt'):match('\nSIZE 320 180 4 6\n'))
end)
check('silent export cannot reuse an old image or descriptor',function()
    write(directory..'/eve-capture-color.png',png); write(directory..'/eve-capture-color.txt','old')
    local b=backend(); b.no_export=true; local c=Capture.new(b,directory)
    assert(not c.capture('color')); assert(not read(directory..'/eve-capture-color.txt'))
    assert(b.actors==0 and b.targets==0)
end)
check('configure failure retains actor ownership until cleanup retry succeeds',function()
    local b=backend(); b.fail_configure=true; b.fail_destroy=true; local c=Capture.new(b,directory)
    assert(not c.capture('color')); assert(c.status()=='cleanup-required'); assert(b.actors==1 and b.targets==0)
    assert(not c.capture('color')); assert(b.spawned==1)
    b.fail_destroy=false; assert(c.cleanup()); assert(c.status()=='idle' and b.actors==0)
end)
check('bind failure owns target and never releases it while actor cleanup fails',function()
    local b=backend(); b.fail_bind=true; b.fail_destroy=true; local c=Capture.new(b,directory)
    assert(not c.capture('color')); assert(b.actors==1 and b.targets==1)
    b.fail_destroy=false; assert(c.cleanup()); assert(b.actors==0 and b.targets==0)
end)
check('release failure is retryable and withholds metadata',function()
    local b=backend(); b.fail_release=true; local c=Capture.new(b,directory)
    assert(not c.capture('color')); assert(b.actors==0 and b.targets==1)
    assert(not read(directory..'/eve-capture-color.txt'))
    b.fail_release=false; assert(c.cleanup()); assert(b.targets==0)
end)
check('capture errors and malformed files clean up without publication',function()
    for _,failure in ipairs({'fail_capture','bad_export'}) do
        local b=backend(); b[failure]=true; local c=Capture.new(b,directory)
        assert(not c.capture('color')); assert(b.actors==0 and b.targets==0)
        assert(not read(directory..'/eve-capture-color.txt'))
    end
end)
check('unsupported modes do not allocate or touch existing capture',function()
    local b=backend(); local c=Capture.new(b,directory)
    write(directory..'/eve-capture-color.txt','preserved')
    assert(not c.capture('../color')); assert(b.spawned==0)
    assert(read(directory..'/eve-capture-color.txt')=='preserved')
end)
check('synchronous BMP comparison reuses one target and leaves paired publication unchanged',function()
    for _,bits in ipairs({24,32}) do
        local b,logs=backend(),{}; b.bmp_image=bmp_hd(bits)
        local c=Capture.new(b,directory,function(message) logs[#logs+1]=message end)
        write(directory..'/eve-frame.txt','preserved paired frame')
        assert(type(c.exportbmp)=='function','The bounded synchronous BMP comparison is missing')
        local ok,message=c.exportbmp(); assert(ok,message)
        assert(message:find(bits..' bpp',1,true) and message:find('compression 0',1,true))
        assert(b.camera_reads==1 and b.spawned==1 and b.captures==3 and b.exports==2 and b.bmp_exports==1)
        assert(b.actors==0 and b.targets==0 and c.status()=='idle')
        assert(read(directory..'/eve-frame.txt')=='preserved paired frame')
        assert(read(directory..'/eve-exportbmp-kismet.png')==png_hd and read(directory..'/eve-exportbmp.bmp')==b.bmp_image)
        local joined=table.concat(logs,'\n')
        for _,label in ipairs({'Kismet cold export','BMP synchronous export','Kismet warm export'}) do
            assert(joined:find(label..' start',1,true) and joined:find(label..' end',1,true))
        end
    end
end)
check('BMP exceptions and invalid headers use existing cleanup and resource retry',function()
    local good=bmp_hd(32)
    local bad_width=good:sub(1,18)..string.pack('<i4',1279)..good:sub(23)
    local compressed=good:sub(1,30)..string.pack('<I4',1)..good:sub(35)
    for _,invalid in ipairs({good:sub(1,80),bad_width,compressed,good..string.rep('\0',4*1024*1024)}) do
        local b=backend(); b.bmp_image=invalid; local c=Capture.new(b,directory)
        assert(not c.exportbmp()); assert(b.actors==0 and b.targets==0 and c.status()=='idle')
        assert(read(directory..'/eve-frame.txt')=='preserved paired frame')
    end
    local b=backend(); b.fail_bmp=true; b.fail_destroy=true; local c=Capture.new(b,directory)
    assert(not c.exportbmp() and c.status()=='cleanup-required' and b.targets==1)
    assert(not c.exportbmp() and b.spawned==1,'Failed cleanup must prevent another allocation')
    b.fail_destroy=false; assert(c.cleanup() and b.targets==0 and c.status()=='idle')
    b=backend(); b.fail_export_at=2; c=Capture.new(b,directory)
    assert(not c.exportbmp() and not read(directory..'/eve-exportbmp-kismet.png'),
        'A silent warm export must not reuse the preceding cold PNG')
    assert(b.actors==0 and b.targets==0 and read(directory..'/eve-frame.txt')=='preserved paired frame')
end)
local frame_files={}
local function track_frame(b) for _,name in ipairs(b.files) do frame_files[name]=true end end
check('HD BMP v2 and explicit PNG fallback share publication and cleanup',function()
    local b,logs=backend(),{}
    local c=Capture.new(b,directory,function(message) logs[#logs+1]=message end)
    assert(c.frame(nil,'hd')); track_frame(b)
    local metadata=read(directory..'/eve-frame.txt')
    assert(metadata:match('^SBMC_FRAME 2 ') and metadata:match('\nSIZE 1280 720\n'))
    assert(metadata:match('\nCOLOR eve%-frame%-%d+%-%d+%-color.bmp %d+\n'))
    for _,label in ipairs({'COLOR','MASK','DEPTH'}) do
        local name,size=metadata:match('\n'..label..' ([a-z0-9%.%-]+) (%d+)\n')
        assert(name and #read(directory..'/'..name)==tonumber(size),'Version 2 must publish each actual byte length')
    end
    assert(read(directory..'/'..b.files[1])==bmp_hd(24) and read(directory..'/'..b.files[2])==png_hd)
    assert(read(directory..'/'..b.files[3])==hdr_hd and b.camera_reads==1 and b.actors==0 and b.targets==0)
    assert(table.concat(b.sources,',')=='2,0,4' and b.cameras[1]==b.cameras[2] and b.cameras[2]==b.cameras[3])
    local joined=table.concat(logs,'\n')
    for _,stage in ipairs({'color allocation','color capture','color export','color validation',
        'mask allocation','mask capture','mask export','mask validation',
        'depth allocation','depth capture','depth export','depth validation','cleanup','publication'}) do
        assert(joined:find(stage..' start',1,true) and joined:find(stage..' end',1,true),'Missing HD stage timing: '..stage)
    end
    local old={table.unpack(b.files)}
    assert(c.frame(nil,'hd-png')); track_frame(b)
    metadata=read(directory..'/eve-frame.txt')
    assert(metadata:match('^SBMC_FRAME 1 ') and metadata:match('\nSIZE 1280 720\n'))
    assert(metadata:match('\nCOLOR eve%-frame%-%d+%-%d+%-color.png %d+\n'))
    for _,name in ipairs(old) do assert(not read(directory..'/'..name),'PNG fallback must retire the preceding BMP frame') end
    local log_count=#logs
    assert(c.frame()); track_frame(b)
    metadata=read(directory..'/eve-frame.txt')
    assert(metadata:match('^SBMC_FRAME 1 ') and metadata:match('\nSIZE 320 180\n') and #logs==log_count)
    local previous=read(directory..'/eve-frame.txt'); local spawned=b.spawned
    assert(not c.frame(nil,'other') and b.spawned==spawned and read(directory..'/eve-frame.txt')==previous)
    assert(c.clear())
end)
check('BMP frame failures retain the last complete generation and allow explicit PNG fallback',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame(nil,'hd')); track_frame(b)
    local previous=read(directory..'/eve-frame.txt'); local old={table.unpack(b.files)}
    for _,failure in ipairs({'exception','layout','mask'}) do
        b.fail_bmp=failure=='exception'
        b.bmp_image=failure=='layout' and bmp_hd(32) or nil
        b.fail_export_at=failure=='mask' and b.exports+1 or nil
        assert(not c.frame(nil,'hd')); track_frame(b)
        assert(read(directory..'/eve-frame.txt')==previous and b.actors==0 and b.targets==0)
        for _,name in ipairs(old) do assert(read(directory..'/'..name)) end
    end
    b.fail_bmp=true; b.bmp_image=nil; b.fail_export_at=nil
    assert(c.frame(nil,'hd-png'),'Explicit PNG fallback must not call the BMP exporter'); track_frame(b)
    assert(read(directory..'/eve-frame.txt'):match('^SBMC_FRAME 1 ')); assert(c.clear())
end)
check('HD rejects mismatched headers and oversized files through shared cleanup',function()
    for _,invalid in ipairs({png,png_hd..string.rep('\0',4*1024*1024)}) do
        local b=backend(); local c=Capture.new(b,directory); assert(c.frame()); track_frame(b)
        local previous=read(directory..'/eve-frame.txt'); b.image=invalid
        assert(not c.frame(nil,'hd')); track_frame(b)
        assert(read(directory..'/eve-frame.txt')==previous and b.actors==0 and b.targets==0)
        assert(c.clear())
    end
end)
check('HD cleanup failure withholds publication and remains retryable',function()
    local b=backend(); local c=Capture.new(b,directory,function() error('optional logger failed') end)
    assert(c.frame()); track_frame(b)
    local previous=read(directory..'/eve-frame.txt'); b.fail_release=true
    assert(not c.frame(nil,'hd')); track_frame(b)
    assert(c.status()=='cleanup-required' and b.targets==3 and read(directory..'/eve-frame.txt')==previous)
    local spawned=b.spawned; assert(not c.frame(nil,'hd') and b.spawned==spawned)
    b.fail_release=false; assert(c.clear()); assert(c.status()=='idle' and b.targets==0)
    assert(c.frame(nil,'hd')); track_frame(b); assert(c.clear(),'Diagnostic logging must not block capture cleanup')
end)
check('paired frame publishes all three sources from exactly one camera snapshot',function()
    local b=backend(); local c=Capture.new(b,directory)
    assert(type(c.frame)=='function','The paired-frame capture API is missing')
    local link={session=string.rep('a',32),ack=7,scale=100,position={x=1.25,y=2,z=-3}}
    assert(c.frame(link)); track_frame(b)
    assert(b.camera_reads==1 and #b.cameras==3 and b.cameras[1]==b.cameras[2] and b.cameras[2]==b.cameras[3])
    assert(table.concat(b.sources,',')=='2,0,4' and b.actors==0 and b.targets==0)
    local meta=assert(read(directory..'/eve-frame.txt')); local lines={}
    for line in meta:gmatch('[^\n]+') do lines[#lines+1]=line end
    assert(#lines==10 and meta:sub(-1)=='\n')
    local id=assert(lines[1]:match('^SBMC_FRAME 1 (%d+%-%d+) %d+$'))
    assert(lines[2]=='SIZE 320 180' and lines[6]=='LINK '..string.rep('a',32)..' 7 100 1.25 2 -3')
    for i,layer in ipairs({'COLOR','MASK','DEPTH'}) do
        local name,size=lines[6+i]:match('^'..layer..' ([a-z0-9%.%-]+) (%d+)$')
        assert(name and name:find(id,1,true) and #read(directory..'/'..name)==tonumber(size))
    end
    assert(lines[10]=='END '..id)
end)
check('failed frame preserves the previous complete descriptor and images',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame()); track_frame(b)
    local previous=assert(read(directory..'/eve-frame.txt')); local names={table.unpack(b.files)}
    b.fail_export_at=b.exports+2
    assert(not c.frame()); track_frame(b)
    assert(read(directory..'/eve-frame.txt')==previous and b.actors==0 and b.targets==0)
    for _,name in ipairs(names) do assert(read(directory..'/'..name)) end
    b.fail_export_at=nil; assert(c.frame()); track_frame(b)
    assert(read(directory..'/eve-frame.txt')~=previous)
    for _,name in ipairs(names) do assert(not read(directory..'/'..name),'Old owned images survive successful replacement') end
end)
check('descriptor rename failure restores the preceding complete frame',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame(nil,'hd')); track_frame(b)
    local previous=assert(read(directory..'/eve-frame.txt')); local names={table.unpack(b.files)}
    assert(previous:match('^SBMC_FRAME 2 '))
    local rename=os.rename
    os.rename=function(from,to)
        if to==directory..'/eve-frame.txt' then return nil,'simulated rename failure',13 end
        return rename(from,to)
    end
    local ran,ok=pcall(c.frame,nil,'hd')
    os.rename=rename
    track_frame(b)
    assert(ran and not ok and read(directory..'/eve-frame.txt')==previous)
    for _,name in ipairs(names) do assert(read(directory..'/'..name)) end
    assert(b.actors==0 and b.targets==0 and c.status()=='idle')
end)
check('failed frame cleanup retains native resources and the previous frame',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame()); track_frame(b)
    local previous=assert(read(directory..'/eve-frame.txt'))
    b.fail_release=true; assert(not c.frame()); track_frame(b)
    assert(read(directory..'/eve-frame.txt')==previous and c.status()=='cleanup-required')
    local count=b.spawned; assert(not c.frame()); assert(b.spawned==count)
    b.fail_release=false; assert(c.cleanup()); assert(b.targets==0 and c.status()=='idle')
end)
check('invalid frame links fail before native allocation',function()
    local b=backend(); local c=Capture.new(b,directory)
    assert(not c.frame({session='../bad',ack=1,scale=100,position={x=0,y=0,z=0}}))
    assert(b.spawned==0 and b.camera_reads==0)
end)
check('clear invalidates the descriptor and removes only owned paired images',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame()); track_frame(b)
    assert(type(c.clear)=='function','Explicit paired-frame invalidation is missing')
    write(directory..'/eve-capture-color.txt','preserved')
    assert(c.clear()); assert(c.status()=='idle' and not read(directory..'/eve-frame.txt'))
    for _,name in ipairs(b.files) do assert(not read(directory..'/'..name)) end
    assert(read(directory..'/eve-capture-color.txt')=='preserved')
end)
check('clear retains descriptor and image deletion obligations for retry',function()
    local b=backend(); local c=Capture.new(b,directory); assert(c.frame()); track_frame(b)
    local remove=os.remove
    os.remove=function(path)
        if path==directory..'/eve-frame.txt' or path==directory..'/'..b.files[1] then return nil,'locked',13 end
        return remove(path)
    end
    local ran,ok=pcall(c.clear)
    os.remove=remove
    assert(ran and not ok and c.status()=='cleanup-required')
    for _,name in ipairs(b.files) do
        assert(read(directory..'/'..name),'A descriptor that cannot be invalidated must retain its complete image generation')
    end
    local spawned=b.spawned; assert(not c.frame()); assert(b.spawned==spawned)
    assert(c.clear()); assert(not read(directory..'/eve-frame.txt'))
    for _,name in ipairs(b.files) do assert(not read(directory..'/'..name)) end
end)
check('repeated frames keep only one complete generation and bounded native handles',function()
    local b=backend(); local c=Capture.new(b,directory)
    local export=b.export
    function b.export(...)
        assert(b.actors<=3 and b.targets<=3)
        export(...)
        local present=0
        for _,name in ipairs(b.files) do if read(directory..'/'..name) then present=present+1 end end
        assert(present<=6,'Replacement retains at most two generations')
    end
    local previous
    for _=1,30 do
        assert(c.frame()); track_frame(b)
        local descriptor=read(directory..'/eve-frame.txt'); assert(descriptor~=previous); previous=descriptor
        local present=0
        for _,name in ipairs(b.files) do if read(directory..'/'..name) then present=present+1 end end
        assert(present==3 and b.actors==0 and b.targets==0)
    end
    assert(c.clear())
end)
for name in pairs(frame_files) do os.remove(directory..'/'..name) end
for _,name in ipairs({'eve-frame.txt','eve-frame.txt.tmp','eve-frame.txt.previous'}) do os.remove(directory..'/'..name) end
for _,name in ipairs({'eve-exportbmp-kismet.png','eve-exportbmp.bmp'}) do os.remove(directory..'/'..name) end
for _,name in ipairs({'eve-capture-color.png','eve-capture-color.txt','eve-capture-color.txt.tmp','eve-capture-depth.hdr','eve-capture-depth.txt'}) do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
check('adapter matches reflected API arity and disables automatic rendering before binding',function()
    local function valid() return true end
    local null={IsValid=function() return false end}
    local target={IsValid=valid}
    local capture={IsValid=valid}
    function capture:ClearShowOnlyComponents(...) assert(select('#',...)==0) end
    function capture:ShowOnlyActorComponents(...) assert(select('#',...)==2); local pawn,children=...; assert(pawn.id==11 and children==true) end
    function capture:CaptureScene(...) assert(select('#',...)==0 and self.TextureTarget==target); self.captured=true end
    local actor={IsValid=valid,CaptureComponent2D=capture}
    function actor:K2_DestroyActor(...) assert(select('#',...)==0 and capture.TextureTarget==null); self.destroyed=true end
    local world={IsValid=valid,GetAddress=function()return 12 end}
    function world:SpawnActor(...) assert(select('#',...)==3); return actor end
    local pawn={id=11,IsValid=valid,GetAddress=function()return 11 end,GetWorld=function()return world end,
        K2_GetActorLocation=function()return {X=7,Y=8,Z=9}end,
        CapsuleComponent={IsValid=valid,GetScaledCapsuleHalfHeight=function()return 95 end}}
    local controller={IsValid=valid,PlayerCameraManager={IsValid=valid,
        GetCameraLocation=function()return {X=1,Y=2,Z=3}end,
        GetCameraRotation=function()return {Pitch=4,Yaw=5,Roll=6}end,GetFOVAngle=function()return 70 end}}
    local lib={IsValid=valid}
    local writer={IsValid=valid}
    function writer:ExportToDisk(...)
        assert(select('#',...)==3,'The pinned ImageWriteQueue function requires three inputs')
        local texture,path,options=...
        assert(texture==target and path==directory..'/test.bmp')
        assert(options.Format==2 and options.CompressionQuality==0 and options.bAsync==false and options.bOverwriteFile==false)
        assert(options.OnComplete==nil and options.NativeOnComplete==nil,'No callback may enter the delegate marshaller')
        local count=0; for _ in pairs(options) do count=count+1 end; assert(count==4)
        self.called=true
    end
    function lib:CreateRenderTarget2D(...)
        assert(select('#',...)==6,'Target-game CreateRenderTarget2D takes six inputs')
        local context,w,h,format,clear,mips=...
        assert(context==pawn and w==320 and h==180 and format==2 and clear.A==1 and mips==false)
        assert(capture.bCaptureEveryFrame==false and capture.bCaptureOnMovement==false)
        return target
    end
    function lib:ExportRenderTarget(...) assert(select('#',...)==4); local owner,texture,dir,name=...; assert(owner==actor and texture==target and dir==directory and name=='eve-capture-color.png') end
    function lib:ReleaseRenderTarget2D(...) assert(select('#',...)==1 and (...)==target and actor.destroyed); self.released=true end
    package.loaded.UEHelpers={GetPlayer=function()return pawn end,GetPlayerController=function()return controller end}
    StaticFindObject=function(path)
        if path=='/Script/ImageWriteQueue.Default__ImageWriteBlueprintLibrary' then return writer end
        return path=='/Script/Engine.SceneCapture2D' and world or lib
    end
    CreateInvalidObject=function()return null end
    local adapter=require('capture_adapter').new()
    local camera=adapter.camera(); assert(camera.pawn_id==11 and camera.world_id==12 and camera.half_height==95)
    local mode={width=320,height=180,source=0,format=2}
    local owned=adapter.spawn(camera); adapter.configure(owned,camera,mode)
    local image=adapter.create_target(camera,mode); adapter.bind(owned,image); adapter.capture(owned)
    adapter.export(owned,image,directory,'eve-capture-color.png')
    adapter.export_bmp(image,directory,'test.bmp'); adapter.destroy(owned); adapter.release(image)
    assert(capture.captured and lib.released and writer.called)
end)
print(count..' capture checks passed; native rendering remains untested.')
