package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Probe=require('pose_probe')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function read(name) local f=assert(io.open(directory..'/'..name,'rb'));local value=f:read('a');f:close();return value end
local now,calls,broken,grow=0,0,false,false
local identity={qx=0,qy=0,qz=0,qw=1,x=0,y=0,z=0,sx=1,sy=1,sz=1}
local engine={}
function engine.components()
    return {{handle='body',name='Body',mesh='CH_P_EVE_01_Body',bones=3,morphs={}},
        {handle='face',name='Face',mesh='CH_P_EVE_Face_003',bones=2,morphs={'eyeBlinkLeft','jawOpen'},parent='Body',socket='Bip001-Head'},
        {handle='suit',name='Suit',mesh='Suit',bones=3,morphs={},leader='Body'}}
end
function engine.snapshot(handle)
    calls=calls+1
    if broken then error('snapshot conversion failed') end
    if handle=='body' then
        local transforms={identity,identity,{qx=0,qy=0,qz=0.5,qw=0.866,x=1,y=2,z=3,sx=1,sy=1,sz=1}}
        if grow then transforms[4]=identity end
        return {names={'Root','Bip001','Bip001-Head'},transforms=transforms,weights={},method='snapshot'}
    end
    return {names={'Root','Jaw'},transforms={identity,identity},weights={0.25,0.75},method='sockets',fallback='SnapshotPose returned mismatched arrays.'}
end
function engine.world() return identity end
local probe=Probe.new(engine,directory,function() return now end)
local ok,message=probe.start()
assert(ok and message:find('3 components',1,true) and probe.active())
assert(not probe.start(),'One recording at a time')
for frame=1,4 do now=now+0.016; assert(probe.tick(frame)) end
assert(calls==8,'Only components without a leader are snapshotted')
now=20; local done,result=probe.tick(5)
assert(done and result:find('Pose probe finished (limit): 4 frames, 3 components.',1,true) and not probe.active())
local summary=read('eve-pose-probe.txt')
assert(summary:find('COMPONENT 1 Body mesh=CH_P_EVE_01_Body bones=3 morphs=0 weights=0 leader=- parent=- socket=- recorded=true method=snapshot',1,true))
assert(summary:find('COMPONENT 2 Face mesh=CH_P_EVE_Face_003 bones=2 morphs=2 weights=2 leader=- parent=Body socket=Bip001-Head recorded=true method=sockets',1,true))
assert(summary:find('COMPONENT 3 Suit mesh=Suit bones=3 morphs=0 weights=0 leader=Body',1,true) and summary:find('recorded=false',1,true))
assert(summary:find('BONES 1 Root Bip001 Bip001-Head',1,true) and summary:find('MORPHS 2 eyeBlinkLeft jawOpen',1,true))
assert(summary:find('FALLBACK 2 SnapshotPose returned mismatched arrays.',1,true))
assert(summary:find('TIMING frame_total count=4',1,true) and summary:find('TIMING snapshot_Body count=4',1,true))
-- Each frame: u32 frame, f64 seconds, then per component its world transform followed by any recorded bones and weights.
local frame_bytes=12+3*40+3*40+2*40+2*4
local data=read('eve-pose-probe.bin')
assert(#data==4*frame_bytes,'Unexpected recording size '..#data)
local frame,seconds=string.unpack('<I4d',data)
assert(frame==1 and math.abs(seconds-0.016)<1e-9)
local body3={string.unpack('<'..string.rep('f',10),data,12+40+2*40+1)}
assert(math.abs(body3[3]-0.5)<1e-6 and body3[5]==1 and body3[6]==2 and body3[7]==3,'Body bone transforms follow the component world transforms')
local weights={string.unpack('<ff',data,12+(40+3*40)+(40+2*40)+1)}
assert(weights[1]==0.25 and weights[2]==0.75)
-- A changed skeleton or a conversion failure stops the probe and still writes evidence.
now=0; assert(probe.start()); assert(probe.tick(1)); grow=true
local stopped,why=probe.tick(2)
assert(not stopped and why:find('error:',1,true) and not probe.active() and read('eve-pose-probe.txt'):find('Bone count changed',1,true))
grow=false; now=0; assert(probe.start()); broken=true
stopped,why=probe.tick(1)
assert(not stopped and read('eve-pose-probe.txt'):find('snapshot conversion failed',1,true) and not probe.active())
broken=false
for name in io.popen('ls '..directory):lines() do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
print('PASS pose probe: leader-aware snapshots, bounded recording, binary layout, fallback reporting and failure evidence')
