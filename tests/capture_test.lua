-- Native Unreal calls are substituted; filesystem publication and ownership are real.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Capture=pcall(require,'capture')
assert(loaded,'The one-shot capture lifecycle module is missing')
local directory=os.tmpname(); os.remove(directory)
assert(os.execute('mkdir '..directory))
local function write(path,data) local f=assert(io.open(path,'wb')); assert(f:write(data)); assert(f:close()) end
local function read(path) local f=io.open(path,'rb'); if not f then return nil end; local s=f:read('*a'); f:close(); return s end
local png='\137PNG\13\10\26\10'..string.pack('>I4',13)..'IHDR'..string.pack('>I4I4',320,180)..string.rep('\0',9)..'\0\0\0\0IEND\174\66\96\130'
local hdr='#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 180 +X 320\n'..string.rep('\2\2\1\64'..string.rep('\255\0\255\0\194\0',4),180)
local function backend()
    local b={actors=0,targets=0,spawned=0}
    function b.camera() return {pawn_id=11,world_id=12,position={X=1,Y=2,Z=3},rotation={Pitch=4,Yaw=5,Roll=6},fov=70,pawn_position={X=7,Y=8,Z=9},half_height=95} end
    function b.spawn() b.actors=b.actors+1; b.spawned=b.spawned+1; return {} end
    function b.configure() if b.fail_configure then error('configure failed') end end
    function b.create_target() b.targets=b.targets+1; return {} end
    function b.bind() if b.fail_bind then error('bind failed') end end
    function b.capture() if b.fail_capture then error('capture failed') end end
    function b.export(_,_,dir,name)
        if b.no_export then return end
        write(dir..'/'..name,b.bad_export and 'wrong format' or (name:match('%.hdr$') and hdr or png))
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
    StaticFindObject=function(path) return path=='/Script/Engine.SceneCapture2D' and world or lib end
    CreateInvalidObject=function()return null end
    local adapter=require('capture_adapter').new()
    local camera=adapter.camera(); assert(camera.pawn_id==11 and camera.world_id==12 and camera.half_height==95)
    local mode={width=320,height=180,source=0,format=2}
    local owned=adapter.spawn(camera); adapter.configure(owned,camera,mode)
    local image=adapter.create_target(camera,mode); adapter.bind(owned,image); adapter.capture(owned)
    adapter.export(owned,image,directory,'eve-capture-color.png'); adapter.destroy(owned); adapter.release(image)
    assert(capture.captured and lib.released)
end)
print(count..' capture checks passed; native rendering remains untested.')
