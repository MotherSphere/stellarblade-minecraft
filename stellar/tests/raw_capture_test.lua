package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Raw=pcall(require,'raw_capture')
assert(loaded,'The native raw capture lifecycle is missing')
local Session=require('capture_session')
local run,session=string.rep('1',32),string.rep('a',32)
local request=Session.parse_request('SBMC_CAPTURE_SESSION 2\nRUN '..run..'\nSESSION '..session..'\nTIME 100\nSIZE 64 48\nSTATE active\nEND\n')
assert(request and request.version==2 and request.width==64 and request.height==48)
for _,size in ipairs({'0 48','64 0','3841 48','64 2161','nan 48'}) do
 assert(not Session.parse_request('SBMC_CAPTURE_SESSION 2\nRUN '..run..'\nSESSION '..session..'\nTIME 100\nSIZE '..size..'\nSTATE active\nEND\n'))
end
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local path=directory..'/eve-frames-'..run..'.bin'
local stride=((4096+64*48*8+63)//64)*64
local function field(offset,format,...)
 local f=assert(io.open(path,'r+b'));assert(f:seek('set',offset));assert(f:write(string.pack(format,...)));f:close()
end
local function header()
 local f=assert(io.open(path,'wb'));f:write('SBMCFRM1',string.pack('<I4I4I4I4I4I4',1,4096,64,48,stride,4096+3*stride),run,session)
 f:seek('set',4096+3*stride-1);f:write('\0');f:close()
 field(256,'<I4',1);field(264,'<I8',100000)
end
header()
local now,spawns,targets,warmups,exports,updates,captures=100,0,0,0,{},0,0
local destroyed,released,fail_destroy={}, {},false
local camera={pawn_id=11,world_id=12,position={X=1,Y=2,Z=3},rotation={Pitch=0,Yaw=10,Roll=0},fov=75,
 pawn_position={X=10,Y=20,Z=30},half_height=95}
local engine={camera=function()return camera end}
function engine.spawn()spawns=spawns+1;return {id=spawns}end
function engine.configure(actor,cam,mode)assert(mode.persistent and mode.width==64 and mode.height==48);actor.mode=mode end
function engine.create_target(_,mode)targets=targets+1;return {id=targets,mode=mode}end
function engine.bind(actor,target)actor.target=target end
function engine.update(actor,cam)assert(actor.target and cam==camera);updates=updates+1 end
function engine.capture(actor)assert(actor.target);captures=captures+1 end
function engine.export(actor,target,dir,name)
 assert(target.mode.source==4 and captures==2,'Only the initial depth export fences both fresh resources')
 warmups=warmups+1
 local f=assert(io.open(dir..'/'..name,'wb'));f:write('#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 48 +X 64\n',string.rep('\0',64));f:close()
end
function engine.export_async(target,dir,name,format)
 assert(warmups==1 and (format==2 or format==3))
 local f=assert(io.open(dir..'/eve-raw-frame.txt','rb'));local meta=f:read('*a');f:close()
 assert(meta:find('SBMC_FRAME 6 ',1,true) and meta:find('COLOR raw 12288',1,true) and meta:find('MASK depth 0',1,true))
 assert(meta:find('RUN '..run,1,true) and name:find('eve-raw-'..run..'-',1,true))
 exports[#exports+1]={name=name,format=format,metadata=meta}
end
function engine.destroy(actor)if fail_destroy then error('destroy failed')end;assert(not destroyed[actor.id]);destroyed[actor.id]=true end
function engine.release(target)assert(not released[target.id]);released[target.id]=true end
local link={session=session,ack=1,scale=100,position={x=0,y=0,z=0}}
local traces={}
local native_frame=1000
local raw=Raw.new(engine,directory,function()return now end,function(message)traces[#traces+1]=message end,
 function()return native_frame end)
assert(raw.start(request));assert(raw.tick(link) and spawns==0,'No capture before native producer readiness')
field(192,'<I4I4',42,1);field(208,'<I8',100000)
assert(raw.tick(link));assert(spawns==2 and targets==2 and warmups==1 and #exports==2)
assert(traces[1] and traces[1]:find('stage=begin',1,true),'Trace submission before the native calls')
assert(traces[#traces]:find('stage=queued',1,true),'Trace the end of the asynchronous submissions')
assert(exports[1].format==3 and exports[1].name:find('%-depth%.exr$') and exports[2].format==2
 and exports[2].name:find('%-color%.bmp$'),'Submit the larger depth readback before color; no coverage pass')
local first=exports[1].metadata
native_frame=native_frame+1
assert(raw.tick(link) and #exports==2,'One generation must wait for native raw completion')
local counter=tonumber(first:match('SBMC_FRAME 6 %d+%-(%d+)'))
field(216,'<I8',counter)
camera.rotation.Yaw=20
native_frame=native_frame+2
assert(raw.tick(link));assert(spawns==2 and targets==2 and warmups==1 and #exports==4 and updates==2)
assert(table.concat(traces,'\n'):find('frame=100-1 stage=complete',1,true),'Trace native completion only after its counter acknowledgement')
assert(exports[3].metadata~=first and captures==4,'Reuse targets while replacing exact camera/frame metadata')
assert(raw.tick(link) and #exports==4)
local cadence=raw.cadence()
assert(cadence.generations==1 and cadence.frames==3 and cadence.histogram[3]==1,'Count native frames from submission to observed completion')
assert(raw.cadence().generations==0,'Cadence reports reset after each read')
for completed=2,12 do field(216,'<I8',completed);assert(raw.tick(link)) end
cadence=raw.cadence()
assert(cadence.generations==11 and cadence.frames==11 and cadence.histogram[1]==11,'A completion is never counted in fewer than one frame')
local trace_count=#traces
field(216,'<I8',13);assert(raw.tick(link))
assert(#traces==trace_count,'Startup tracing must stop after twelve frames')
local submitted=#exports
fail_destroy=true;assert(not raw.clear() and raw.busy() and next(released)==nil,'Never release still-bound targets')
fail_destroy=false;assert(raw.clear() and not raw.busy());assert(released[1] and released[2] and not released[3])
-- A stopped mapping must never produce more work or reactivate an old RUN.
assert(raw.start(request));field(256,'<I4',2);assert(not raw.tick(link) and #exports==submitted)
assert(raw.clear())
-- A malformed mapping never authorizes a native call.
header();field(16,'<I4',3841)
assert(raw.start(request));assert(not raw.tick(link) and #exports==submitted);assert(raw.clear())
for name in io.popen('ls '..directory):lines() do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..directory))
print('PASS raw capture: strict request/header, producer readiness, two persistent targets, one warmup, depth-first v6 planes, cadence, completion ownership and cleanup')
