-- Exercise the real command and scheduling integration without invoking Unreal.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local queued,keys={},{}
local loop,hd_loop,console,frame_hook,hd_delay
local frames,stills,clears,returns=0,0,0,0
local bmp_exports=0
local async_exports,async_frames,async_link=0,0,nil
local hd_active,hd_pending,hd_ticks,hd_stops,hd_cancels=false,false,0,0,0
local hd_error=false
local profiles={}
local state='idle'
local tick_error,clear_error,read_error=false,false,false
local report_error=false
local logs={}
local print_message=print
print=function(message)
    if report_error and message:find('Native live capture stopped',1,true) then error('report failed') end
    logs[#logs+1]=message
    print_message(message)
end
local capture={status=function()return 'idle' end,stream_busy=function()return hd_active or hd_pending end}
function capture.frame(_,profile) frames=frames+1; profiles[frames]=profile or 'default'; if frame_hook then frame_hook() end; return true,'frame' end
function capture.capture() stills=stills+1; return true,'still' end
function capture.frameasync(link) async_frames=async_frames+1; async_link=link; return true,'pending async frame' end
function capture.exportasync() async_exports=async_exports+1; return true,'async submissions' end
function capture.exportbmp() bmp_exports=bmp_exports+1; return true,'BMP comparison' end
function capture.cleanup() return true,'cleaned' end
function capture.clear() clears=clears+1; if clear_error then error('native cleanup failed') end; return true,'cleared' end
local bridge={status=function()return state end,shapes=function()return {} end}
local terrain_session
function bridge.read() if read_error then error('bridge read failed') end; return {session=terrain_session} end
function bridge.create() state='ready'; return true,'created' end
function bridge.enter() state='entered'; return true,'entered' end
function bridge.restore() returns=returns+1; state='idle'; return true,'returned' end
function bridge.tick() if tick_error then error('bridge failed') end end
local bridge_steps=0
function bridge.step() bridge_steps=bridge_steps+1 end
function bridge.pose()
    if state~='entered' then return nil end
    return {session=string.rep('a',32),ack=1,state=state,position={x=0,y=0,z=0},anchor={X=1,Y=2,Z=3}}
end
local world={IsValid=function()return true end,GetAddress=function()return 12 end,GetFullName=function()return 'TestWorld' end}
local pawn={IsValid=function()return true end,GetWorld=function()return world end,GetAddress=function()return 11 end,
    GetFullName=function()return 'TestPawn' end,K2_GetActorLocation=function()return {X=10,Y=20,Z=300} end,
    K2_GetActorRotation=function()return {Pitch=0,Yaw=0,Roll=0} end,
    CapsuleComponent={IsValid=function()return true end,GetScaledCapsuleHalfHeight=function()return 95 end,GetScaledCapsuleRadius=function()return 35 end}}
local timing_available,frame_samples,time_samples=true,0,0
local native_frame,frame_counter_error=1200,false
local system={IsValid=function()return true end,LineTraceSingle=function()return false end}
function system:GetFrameCount(...)
    assert(select('#',...)==0); frame_samples=frame_samples+1
    if frame_counter_error then error('native frame counter failed') end
    return native_frame
end
local gameplay={IsValid=function()return true end}
local paused,cinematic,input_context_error,local_controller=true,false,false,true
function gameplay:IsGamePaused(value) assert(value==world); return paused end
function pawn:IsAttachedToLevelSequenceWithoutPlayable()
    if input_context_error then error('context unavailable') end
    return cinematic
end
local controller={IsValid=function()return true end,GetWorld=function()return world end,
    IsLocalPlayerController=function()return local_controller end,K2_GetPawn=function()return pawn end}
function gameplay:GetRealTimeSeconds(...) assert(select('#',...)==1 and (...)==world); time_samples=time_samples+1; return 42.5 end
function system:GetConsoleVariableIntValue(name)assert(name=='t.IdleWhenNotForeground');return 0 end
function system:GetConsoleVariableFloatValue(name)assert(name=='t.MaxFPS');return 60 end
StaticFindObject=function(path)
    if path=='/Script/Engine.TriggerBox' or path=='/Script/Engine.StaticMeshActor' then return {IsValid=function()return true end} end
    if timing_available then
        if path=='/Script/Engine.Default__KismetSystemLibrary' then return system end
        if path=='/Script/Engine.Default__GameplayStatics' then return gameplay end
    end
    return {IsValid=function()return false end}
end
package.loaded.UEHelpers={GetPlayer=function()return pawn end,GetKismetSystemLibrary=function()return system end,
    GetPlayerController=function()return controller end}
local ordinary_creates,probe_returns,probe_state,return_error=0,0,'idle',false
local diagnostic={status=function()return probe_state end,shapes=function()return {} end}
function diagnostic.create() ordinary_creates=ordinary_creates+1; return true,'diagnostic created' end
function diagnostic.restore() probe_returns=probe_returns+1; if return_error then error('return threw') end; return false,'No fixture is active' end
package.loaded.probe={new=function()return diagnostic end}
package.loaded.bridge_config={scale=100,directory='/unused'}
local terrain_engine
package.loaded.terrain={new=function(engine) terrain_engine=engine; return {} end}
package.loaded.bridge={new=function()return bridge end}
package.loaded.capture={new=function(_,directory,logger) assert(directory=='/unused' and type(logger)=='function'); return capture end}
package.loaded.capture_adapter={new=function()return {} end}
package.loaded.capture_session={new=function(received,pose,directory,scale,allowed)
    assert(received==capture and directory=='/unused' and scale==100)
    return {active=function()return hd_active end,busy=function()return hd_active or hd_pending end,
        binding=function() return hd_active and {run=string.rep('c',32),session=string.rep('a',32)} or nil end,
        read=function()return {} end,
        tick=function() hd_ticks=hd_ticks+1; if hd_error then error('HD tick failed') end; return true end,
        stop=function()hd_stops=hd_stops+1; hd_active=false; return true,'HD stopped' end,
        cancel=function()hd_cancels=hd_cancels+1; hd_active=false end}
end}
local permit_context,permit_ticks,permit_stops,permit_revokes=nil,0,0,0
package.loaded.control_permit={new=function(directory)
    assert(directory=='/unused')
    return {tick=function(binding,context) permit_ticks=permit_ticks+1; permit_context=context; return true end,
        stop=function() permit_stops=permit_stops+1; permit_context=nil; return true end,
        revoke=function() permit_revokes=permit_revokes+1; permit_context=nil; return true end}
end}

local visual_toggles,visual_clears,visual_ticks,visual_error=0,0,0,false
local visual={}
function visual.toggle() visual_toggles=visual_toggles+1; return true,'texture toggled' end
function visual.clear() visual_clears=visual_clears+1; if visual_error then error('visual cleanup threw') end; return true,'texture removed' end
function visual.tick() visual_ticks=visual_ticks+1; return true end
package.loaded.visual_probe={new=function(_,directory) assert(directory=='/unused'); return visual end}
package.loaded.visual_probe_adapter={new=function()return {} end}
local scene_enabled,scene_clears,scene_error=false,0,false
local scene={enabled=function()return scene_enabled end,status=function()return scene_enabled and 'active' or 'idle' end,
    read=function()return nil end,tick=function()return true end}
function scene.toggle() scene_enabled=not scene_enabled; return true,'native scene toggled' end
function scene.clear() scene_clears=scene_clears+1; scene_enabled=false; if scene_error then error('scene cleanup threw') end; return true,'native scene removed' end
local pose_starts,pose_frames=0,{}
package.loaded.pose_adapter={new=function()return {} end}
package.loaded.pose_probe={new=function(engine,directory,clock,logger)
    assert(type(engine)=='table' and directory=='/unused' and clock==os.clock and type(logger)=='function')
    return {start=function() pose_starts=pose_starts+1; return true,'pose recording' end,
        tick=function(frame) pose_frames[#pose_frames+1]=frame; return true end}
end}
local stream_ticks,stream_link,stream_error,stream_binding=0,nil,false,nil
package.loaded.pose_stream={new=function(engine,directory,clock,logger,link)
    assert(type(engine)=='table' and directory=='/unused' and clock==os.time and type(logger)=='function' and type(link)=='function')
    stream_link=link
    return {tick=function() stream_ticks=stream_ticks+1; if stream_error then error('identities failed') end; return true end,
        binding=function() return stream_binding end}
end}
local request_args,request_ticks,request_error=nil,0,false
package.loaded.requests={new=function(directory,wall,logger,linked,offered,run)
    assert(directory=='/unused' and wall==os.time and type(logger)=='function')
    request_args={linked=linked,offered=offered,run=run}
    return {tick=function() request_ticks=request_ticks+1; if request_error then error('request read failed') end end}
end}
local hud_args,hud_ticks,hud_error,hud_after=nil,0,false,nil
package.loaded.hud_items={new=function(engine,directory,wall,logger,link)
    assert(type(engine)=='table' and directory=='/unused' and wall==os.time and type(logger)=='function' and type(link)=='function')
    hud_args={link=link}
    return {tick=function() hud_ticks=hud_ticks+1; hud_after=request_ticks; if hud_error then error('widgets unreadable') end end}
end}
package.loaded.native_scene={new=function(_,received,directory,scale) assert(received==bridge and directory=='/unused' and scale==100); return scene end}
package.loaded.scene_adapter={new=function()return {} end}
Key={F1='F1',F2='F2',F4='F4',F5='F5',F6='F6',F7='F7',F8='F8',F9='F9',F11='F11'}
ModifierKey={SHIFT='SHIFT'}
RegisterKeyBind=function(key,modifiers,callback)
    if callback then
        assert((key==Key.F7 or key==Key.F8) and #modifiers==1 and modifiers[1]==ModifierKey.SHIFT)
        keys['Shift+'..key]=callback
    else keys[key]=modifiers end
end
RegisterConsoleCommandHandler=function(name,callback) assert(name=='sbmc'); console=callback end
ExecuteInGameThread=function(callback)
    queued[#queued+1]=callback
end
LoopAsync=function() error('Bridge state must never run on the asynchronous Lua thread') end
LoopInGameThreadWithDelay=function(milliseconds,callback)
    if milliseconds==200 then loop=callback elseif milliseconds==1 or milliseconds==50 then hd_loop=callback;hd_delay=milliseconds else error('Unexpected polling interval') end
end
dofile('stellar/Mods/StellarCollisionProbe/Scripts/main.lua')
local function drain() while #queued>0 do table.remove(queued,1)() end end
local function command(name) console(nil,{name}); drain() end
command('exporttest')
assert(logs[#logs]:find('Async export test is disabled',1,true),
    'The unsupported async export command must fail without calling native export')
assert(frames==0 and stills==0 and clears==0,'Disabled export must not touch capture resources')
assert(keys['Shift+F7'],'The explicit Shift+F7 texture binding is missing')
assert(keys['Shift+F8'],'The explicit Shift+F8 native scene binding is missing')
assert(keys.F4 and not keys['Shift+F4'],'HD capture must not require Shift, which triggers native dodge')
assert(visual_toggles==0,'The texture probe must remain inactive at startup')
command('texture'); keys['Shift+F7'](); drain(); assert(visual_toggles==2)
keys.F7(); drain(); assert(ordinary_creates==1 and visual_toggles==2,'Bare F7 must retain the original fixture action')
keys.F11(); drain(); assert(probe_returns==1 and visual_clears==1 and clears==1)
assert(scene_clears==1,'F11 must also clear native scenes')
assert(logs[#logs]:find('OK:',1,true),'Texture-only cleanup with an idle scene must succeed')
scene_error=true; visual_error=true; clear_error=true; keys.F11(); drain(); scene_error=false; visual_error=false; clear_error=false
assert(probe_returns==2 and visual_clears==2 and clears==2,'F11 must attempt all three stages even if both optional cleanups throw')
assert(scene_clears==2 and logs[#logs]:find('scene cleanup threw',1,true),'A native scene cleanup exception must not skip later stages')
assert(logs[#logs]:find('STOP:',1,true) and logs[#logs]:find('visual cleanup threw',1,true))
return_error=true; keys.F11(); drain(); return_error=false
assert(probe_returns==3 and logs[#logs]:find('return threw',1,true),'Thrown idle return errors must remain visible')
probe_state='ready'; keys.F11(); drain(); probe_state='idle'
assert(logs[#logs]:find('STOP:',1,true),'A non-idle return failure must remain a failure')
keys.F6(); drain(); assert(frame_samples==1 and time_samples==1)
local timing_logged=false
for _,message in ipairs(logs) do if message:find('native_frame_count=1200',1,true) and message:find('native_real_seconds=42.500000',1,true) then timing_logged=true end end
assert(timing_logged,'F6 must log both native counters from one callback')
assert(table.concat(logs,'\n'):find('idle_when_background=0 max_fps=60.000',1,true),'Timing must identify a native background or frame cap without changing it')
timing_available=false; keys.F6(); drain(); timing_available=true
assert(logs[#logs]:find('Native timing unavailable',1,true),'Unavailable timing must be nonfatal and explicit')
assert(keys.F1,'F1 live capture toggle is missing')
loop(); drain(); assert(frames==0,'Startup must not capture')
assert(visual_ticks>0 and frame_samples==1 and time_samples==1,'Ticks must check the visual lifecycle without sampling F6 counters')
command('live'); loop(); drain(); assert(frames==0,'Unentered scene must not capture')
command('visuals'); assert(not scene_enabled,'Unentered scene cannot enable native visuals')
command('mirror'); command('enter'); command('live')
local scene_start_clears=clears; keys['Shift+F8'](); drain()
assert(scene_enabled and clears==scene_start_clears+1,'Native visuals must stop live capture before activation')
command('live'); keys.F1(); drain(); loop(); drain()
assert(frames==0,'Both live start paths must reject simultaneous native scene rendering')
keys.F4(); keys.F2(); drain(); command('capture'); command('frame'); command('framehd'); command('framehdpng'); command('exportbmp'); command('exportasync'); command('frameasync')
assert(frames==0 and stills==0 and bmp_exports==0 and async_exports==0 and async_frames==0,'All still export commands must reject native visual mode')
command('visuals'); assert(not scene_enabled); keys.F1(); drain()
loop(); drain(); assert(frames==1,'F1 should enable the entered link')
keys.F4(); keys.F2(); drain(); command('capture'); command('frame'); command('framehd'); command('framehdpng'); command('exportbmp'); command('exportasync'); command('frameasync')
assert(frames==1 and stills==0 and bmp_exports==0 and async_exports==0 and async_frames==0,'Manual captures must not overlap live capture')
frame_hook=function() loop(); assert(#queued==0,'The pending gate was released during native capture') end
loop(); assert(#queued==0,'A native timer must execute directly without enqueueing another Lua callback')
drain(); frame_hook=nil; assert(frames==2)
keys.F1(); drain(); loop(); drain(); assert(frames==2,'F1 should stop capture')
command('live'); tick_error=true; loop(); drain(); tick_error=false
loop(); drain(); assert(frames==2,'Bridge failures must disable capture')
command('live'); tick_error=true; report_error=true; loop()
local ran=pcall(drain)
report_error=false; tick_error=false
assert(ran,'Unexpected cleanup reporting failure must not escape the game-thread callback')
loop(); assert(#queued==0,'An unexpected callback failure must leave the native timer usable'); drain()
assert(frames==2,'A callback failure must cancel capture')
command('live'); read_error=true; loop(); drain(); read_error=false
loop(); drain(); assert(frames==2,'A bridge read exception must cancel capture')
command('live'); clear_error=true; keys.F11(); drain()
assert(returns==1,'F11 must attempt the native return even when capture cleanup throws')
clear_error=false; loop(); drain(); assert(frames==2)
command('mirror'); command('enter'); command('live'); command('liveoff'); loop(); drain()
assert(frames==2,'Explicit liveoff must prevent subsequent captures')
command('frame'); command('capture'); command('depth')
assert(frames==3 and stills==2,'Still export commands must remain available after native visuals are disabled')
command('framehd'); keys.F4(); drain(); command('frame')
assert(frames==6 and profiles[1]=='default' and profiles[2]=='default' and profiles[3]=='default'
    and profiles[4]=='hd' and profiles[5]=='hd' and profiles[6]=='default',
    'Bare F4 and framehd must request HD; frame and live frames must retain default dimensions')
print('PASS HD still/live/texture commands, F11 cleanup independence, native F6 counters, and pending gate through capture')
command('exportbmp'); assert(bmp_exports==1 and frames==6 and stills==2,'BMP comparison must remain opt-in and separate from paired frames')
command('framehdpng'); assert(frames==7 and profiles[7]=='hd-png','The HD PNG fallback must select its explicit profile')

command('exportasync'); assert(async_exports==1 and frames==7 and stills==2,'Async export must be opt-in and separate from paired frames')

command('frameasync'); assert(async_frames==1 and async_link.session==string.rep('a',32) and async_link.ack==1 and async_link.scale==100)
keys.F11(); drain(); command('frameasync'); assert(async_frames==2 and async_link==nil,'Unlinked async still must preserve the ordinary camera context gate')

assert(hd_loop and hd_ticks==0,'The two game-thread timers retain the separate terrain and HD intervals')
local function next_hd_frame()
    native_frame=native_frame+1
    hd_loop(); drain()
end
hd_loop(); assert(#queued==0 and hd_ticks==1,'The HD timer must execute without an asynchronous handoff'); drain()
local same_frame_permits=permit_ticks
hd_loop(); hd_loop(); drain()
assert(hd_ticks==1 and permit_ticks==same_frame_permits,'Repeated callbacks in one native frame must not repeat HD or control work')
assert(hd_delay==1,'The HD timer must check completed frames at the next native tick')
next_hd_frame(); assert(hd_ticks==2,'The next native frame must progress without a 50 ms pacing gate')
local old_hd_cancels,old_permit_revokes=hd_cancels,permit_revokes
hd_error=true; next_hd_frame(); hd_error=false
assert(hd_cancels==old_hd_cancels+1 and permit_revokes>old_permit_revokes,'An HD failure must still cancel capture and release native input')
hd_loop(); drain(); assert(hd_ticks==3,'A failed frame must not be retried within that native frame')
next_hd_frame(); assert(hd_ticks==4,'An HD exception must release the game-thread scheduling gate')
old_hd_cancels,old_permit_revokes=hd_cancels,permit_revokes
frame_counter_error=true; next_hd_frame(); frame_counter_error=false
assert(hd_cancels==old_hd_cancels+1 and permit_revokes>old_permit_revokes,'Frame counter failure must cancel capture and release native input')
hd_loop(); drain(); assert(hd_ticks==5,'A frame counter exception must not leave the game-thread gate locked')
hd_pending=true
local before_bmp,old_texture,old_scene=bmp_exports,visual_toggles,scene_enabled
command('exportbmp'); command('texture'); command('visuals'); command('live')
assert(bmp_exports==before_bmp and visual_toggles==old_texture and scene_enabled==old_scene and not hd_active,
    'Pending HD output must exclude diagnostic capture, legacy live and native visuals')
local old_hd_stops=hd_stops; keys.F11(); drain(); assert(hd_stops==old_hd_stops+1 and hd_pending,'F11 must stop HD without discarding pending output')
hd_pending=false
command('mirror'); command('enter'); hd_active=true; paused=false
next_hd_frame()
assert(permit_context and permit_context.pawn==11 and permit_context.world==12,'The entered HD session must publish a checked native permit')
paused=true; next_hd_frame(); assert(not permit_context,'Native pause must neutralize input')
paused=false; cinematic=true; next_hd_frame(); assert(not permit_context,'An unplayable sequence must neutralize input')
cinematic=false; local_controller=false; next_hd_frame(); assert(not permit_context,'Only the local controller is eligible')
local_controller=true; input_context_error=true; local before_revoke=permit_revokes
next_hd_frame(); assert(permit_revokes>before_revoke,'Native getter failure must revoke the permit')
input_context_error=false; next_hd_frame(); assert(permit_context)
local before_stop=permit_stops; keys.F11(); drain()
assert(permit_stops>before_stop and not permit_context,'F11 must revoke input independently of image cleanup')
print('PASS native control permission gates: local entered context, pause, cinematics, getter failure and F11')

-- Cadence diagnostics: one bounded line per ten native seconds, idle or live.
local real_time,wall,cadence_error=100,0,false
function gameplay:GetRealTimeSeconds(value)
    assert(value==world); if cadence_error then error('real time unavailable') end; return real_time
end
local wall_clock=os.time
os.time=function() return wall end
command('mirror'); command('enter')
local function cadence_lines()
    local count=0
    for _,message in ipairs(logs) do if message:find('Cadence:',1,true) then count=count+1 end end
    return count
end
local function seconds(value) wall=wall+1; real_time=real_time+value; next_hd_frame() end
local before=cadence_lines()
seconds(0); for _=1,3 do seconds(2.5) end
assert(cadence_lines()==before,'Report only after ten native seconds')
seconds(2.5)
assert(cadence_lines()==before+1 and logs[#logs]:find('Cadence: 0.4 native FPS over 10.0 s; capture idle; 0 generations',1,true),
    'Report native frames per real second and capture state')
real_time=real_time+20; native_frame=native_frame+1; hd_loop(); drain()
assert(cadence_lines()==before+1,'Callbacks within one wall second never report again')
real_time=real_time-20
hd_active=true; for _=1,4 do seconds(2.5) end
assert(cadence_lines()==before+2 and logs[#logs]:find('capture live',1,true),'Report live capture cadence in the same session')
local cancels=hd_cancels
cadence_error=true; seconds(2.5); cadence_error=false
assert(hd_cancels==cancels,'Unavailable diagnostics must not cancel capture or input')
real_time=50; seconds(0); for _=1,3 do seconds(2.5) end
assert(cadence_lines()==before+2,'A world clock reset starts a new window')
os.time=wall_clock
hd_active=false; keys.F11(); drain()
print('PASS bounded native cadence reports: idle and live, wall-second gate, nonfatal diagnostics')
command('posetest'); assert(pose_starts==1 and logs[#logs]:find('OK: pose recording',1,true),'The console command starts one pose recording')
local steps=bridge_steps
next_hd_frame(); assert(pose_frames[#pose_frames]==native_frame,'Each native frame ticks the pose probe once with its frame count')
assert(bridge_steps==steps+1,'Each native frame continues the terrain import once')
print('PASS pose probe console command and per-frame tick')
local ticks=stream_ticks
loop(); drain(); assert(stream_ticks==ticks+1,'The bridge timer checks the pose stream')
assert(stream_link()==nil,'No placement before the link is entered')
command('mirror'); command('enter')
local place=stream_link()
assert(place and place.session==string.rep('a',32) and place.anchor.Z==3 and place.scale==100,'The entered link fixes the placement')
stream_error=true; loop(); drain(); stream_error=false
assert(logs[#logs]:find('Pose stream paused',1,true) and logs[#logs]:find('identities failed',1,true),'Stream failures are reported, not fatal')
loop(); drain(); assert(stream_ticks==ticks+3,'The bridge timer keeps running after a stream failure')
-- Without continuous capture, the avatar stream's binding grants the native input permit.
stream_binding={run=string.rep('d',32),session=string.rep('a',32)}
next_hd_frame(); assert(permit_context and permit_context.pawn==11,'The avatar binding publishes a checked native permit')
stream_binding={run=string.rep('d',32),session=string.rep('e',32)}
next_hd_frame(); assert(not permit_context,'A binding for another link session is refused')
stream_binding=nil; next_hd_frame(); assert(not permit_context,'No binding, no permit')
keys.F11(); drain()
print('PASS pose stream wiring: bridge-timer checks, entered placement, nonfatal failures and avatar input permits')
-- Minecraft's session requests: ticked by the bridge timer after its own tick, through the same run as F5, F8 and F11.
ticks=request_ticks
loop(); drain(); assert(request_ticks==ticks+1,'The bridge timer checks session requests')
request_error=true; loop(); drain(); request_error=false
assert(logs[#logs]:find('STOP: Session requests paused: ',1,true) and logs[#logs]:find('request read failed',1,true),'Request failures are reported')
loop(); drain(); assert(request_ticks==ticks+3,'The bridge timer keeps running after a request failure')
assert(state=='idle' and request_args.linked()==nil and request_args.offered()==nil,'No link, no terrain session')
terrain_session=string.rep('a',32); assert(request_args.offered()==terrain_session,'The terrain names its session')
local requested,message=request_args.run('mirror')
assert(requested and message=='created' and state=='ready' and logs[#logs]:find('OK: created',1,true),'Import runs the F5 action and logs it')
requested,message=request_args.run('enter')
assert(requested and message=='entered' and request_args.linked()==string.rep('a',32),'Enter runs the F8 action; the link names its session')
local returned=returns
requested,message=request_args.run('restore')
assert(requested and returns==returned+1 and state=='idle' and request_args.linked()==nil and message:find('Return: returned',1,true),
    'Return runs the complete F11 cleanup')
requested,message=request_args.run('nonsense')
assert(requested==false and message:find('Use status',1,true) and logs[#logs]:find('STOP: ',1,true),'A failed action reports false and its message')
print('PASS session request wiring: bridge-timer ticks, nonfatal failures, link and terrain sessions, shared key actions')
-- Stellar Blade's HUD slots: read by the bridge timer after session requests, for the link the pose stream follows.
ticks=hud_ticks
loop(); drain(); assert(hud_ticks==ticks+1 and hud_after==request_ticks,'The bridge timer reads the HUD slots after session requests')
hud_error=true; loop(); drain(); hud_error=false
assert(logs[#logs]:find('STOP: HUD slots paused: ',1,true) and logs[#logs]:find('widgets unreadable',1,true),'HUD failures are reported')
loop(); drain(); assert(hud_ticks==ticks+3,'The bridge timer keeps running after a HUD failure')
assert(hud_args.link==stream_link,'The HUD slots follow the entered link')
print('PASS HUD slot wiring: bridge-timer reads after session requests, nonfatal failures, the entered link')
-- The terrain's actors: the cube StaticFindObject never finds is loaded once and kept; a parked box is placed again.
local function fake_actor()
    local actor={IsValid=function()return true end,hidden=false}
    local component={IsValid=function()return true end,IsA=function()return true end}
    for _,name in ipairs({'SetMobility','SetGenerateOverlapEvents','SetBoxExtent','SetCollisionObjectType','SetCollisionResponseToAllChannels'}) do
        component[name]=function() end
    end
    function component:SetCollisionEnabled(mode) self.mode=mode end
    function component:GetCollisionEnabled() return self.mode end
    function component:SetStaticMesh(mesh) self.mesh=mesh; return true end
    actor.CollisionComponent,actor.StaticMeshComponent=component,component
    function actor:GetWorld() return world end
    function actor:SetActorEnableCollision(enabled) self.collision=enabled end
    function actor:K2_SetActorLocation(at) self.at=at; return true end
    function actor:K2_GetActorLocation() return self.at end
    function actor:SetActorScale3D() end
    function actor:SetActorHiddenInGame(hidden) self.hidden=hidden end
    return actor
end
function world:SpawnActor(_,at) local actor=fake_actor(); actor.at=at; return actor end
local loads,load_error,mesh=0,false,nil
LoadAsset=function(path)
    assert(path=='/Engine/BasicShapes/Cube.Cube' and not (mesh and mesh.valid),'A kept cube is never loaded again')
    loads=loads+1
    if load_error then error('asset registry unavailable') end
    mesh={valid=true,IsValid=function(self) return self.valid end}
    return mesh,true,true
end
local box={role='minecraft',center={X=1,Y=2,Z=3},extent={X=50,Y=50,Z=50}}
local function spawn(spec) local handle=terrain_engine.spawn(spec); terrain_engine.configure(handle,spec); return handle end
local handles={spawn(box),spawn(box),spawn(box)}
assert(loads==1 and handles[3].visual.StaticMeshComponent.mesh==mesh,'One load serves every box')
local parked=handles[1]
terrain_engine.park(parked)
assert(parked.actor.CollisionComponent.mode==0 and parked.actor.collision==false and parked.visual.hidden
    and parked.actor.at.Z==3+10000,'Parking takes collision away, hides the visual and lifts the box')
local visual=parked.visual
terrain_engine.configure(parked,{role='minecraft',center={X=7,Y=8,Z=9},extent={X=50,Y=50,Z=100},visuals_visible=true})
assert(parked.visual==visual and visual.at.Z==9 and not visual.hidden and parked.actor.at.Z==9
    and parked.actor.CollisionComponent.mode==3 and parked.actor.collision==true and loads==1,'A reused box keeps its visual')
mesh.valid=false; spawn(box)
assert(loads==2,'A cube the engine deleted is loaded once more')
local function unavailable()
    local count=0
    for _,message in ipairs(logs) do if message:find('Optional cube visuals unavailable',1,true) then count=count+1 end end
    return count
end
mesh.valid=false; load_error=true; spawn(box); spawn(box)
assert(loads==3 and unavailable()==1,'A failed load is reported once and not retried')
print('PASS terrain actors: the cube loaded once and kept (once more if deleted, a failed load reported once), parked boxes placed again with their visuals')
-- The remote lab site's engine: the site by level, Stellar Blade's streaming pin, its ground, its flush, its autosave.
assert(terrain_engine.snapshot().site==nil,'an unknown level gets no site')
function world:GetFullName() return 'World /Game/Art/BG/WorldMap/E05_GreatDesert_P/E05_GreatDesert_P.E05_GreatDesert_P' end
local site=terrain_engine.snapshot().site
assert(site and site.X==-750000 and site.Y==-750000,'the Great Desert has its site')
local streaming={location={X=0,Y=0,Z=0},enabled=false,writes=0,tables=true}
setmetatable(world,{__index=function(_,key)
    if key=='FixedStreamingLocation' then return streaming.location end
    if key=='bEnableFixedStreamingLocation' then return streaming.enabled end
end,__newindex=function(_,key,value)
    streaming.writes=streaming.writes+1
    if key=='bEnableFixedStreamingLocation' then if not streaming.ignore_flag then streaming.enabled=value end
    elseif key=='FixedStreamingLocation' then
        if streaming.tables then streaming.location={X=value.X,Y=value.Y,Z=value.Z} end
    else error('unexpected world write '..key) end
end})
terrain_engine.stream_at({X=1,Y=2,Z=3})
assert(streaming.enabled and streaming.location.X==1 and streaming.location.Z==3 and streaming.writes==2,'the location, then the flag')
terrain_engine.stream_at({X=1,Y=2,Z=3}); assert(streaming.writes==2,'a pin in place writes nothing')
terrain_engine.stream_at({X=4,Y=5,Z=6},true); assert(streaming.writes==2 and streaming.location.X==1,'if_unpinned keeps a pin')
terrain_engine.stream_at(nil); assert(not streaming.enabled and streaming.writes==3)
terrain_engine.stream_at(nil); assert(streaming.writes==3,'an unset pin writes nothing')
streaming.tables=false -- The vector property refuses a table: written one component at a time.
terrain_engine.stream_at({X=4,Y=5,Z=6})
assert(streaming.enabled and streaming.location.X==4 and streaming.location.Y==5 and streaming.location.Z==6)
streaming.location=setmetatable({},{__index=function() return 0 end,__newindex=function() end})
local pinned,why=pcall(terrain_engine.stream_at,{X=7,Y=8,Z=9})
assert(not pinned and tostring(why):find('did not accept the streaming location',1,true),'a pin that never lands fails')
streaming.location={X=0,Y=0,Z=0}; streaming.enabled=false
streaming.ignore_flag=true
pinned,why=pcall(terrain_engine.stream_at,{X=7,Y=8,Z=9})
assert(not pinned and tostring(why):find('did not accept the streaming pin',1,true),'a flag that never lands fails')
streaming.ignore_flag=nil
paused=true; assert(terrain_engine.paused()==true)
paused=false; assert(terrain_engine.paused()==false)
local traced
function system:LineTraceSingle(context,from,to) traced={context,from,to}; return false end
assert(terrain_engine.ground_at({X=1,Y=2,Z=300},95)==false and traced[1]==pawn and traced[3].Z==300-95-30,'a downward trace from the start')
function system:LineTraceSingle() return true end
assert(terrain_engine.ground_at({X=1,Y=2,Z=300},95)==true)
local flushed
function gameplay:FlushLevelStreaming(context) flushed=context end
terrain_engine.flush_streaming(); assert(flushed==world)
controller.bUseAutoSave=true
assert(terrain_engine.autosave(false)==true and controller.bUseAutoSave==false)
assert(terrain_engine.autosave(true)==false and controller.bUseAutoSave==true)
controller.bUseAutoSave=nil
local switched
switched,why=pcall(terrain_engine.autosave,false)
assert(not switched and tostring(why):find('autosave switch unavailable',1,true),'no switch, no change')
setmetatable(controller,{__index=function(_,key) if key=='bUseAutoSave' then return true end end,__newindex=function() end})
switched,why=pcall(terrain_engine.autosave,false)
assert(not switched and tostring(why):find('did not accept the autosave switch',1,true),'a switch that never lands fails')
setmetatable(controller,nil); controller.bUseAutoSave=true
local function tile(loaded) return {get=function() return {IsLevelLoaded=function() return loaded end} end} end
rawset(world,'WorldComposition',{TilesStreaming={ForEach=function(_,each)
    for i,t in ipairs({tile(true),tile(false),tile(false)}) do each(i,t) end
end}})
function system:LineTraceSingle() return false end
keys.F6(); drain()
local counted
for _,message in ipairs(logs) do if message:find('World Composition tiles loaded: 1 of 3; streaming follows the camera',1,true) then counted=true end end
assert(counted,'F6 counts the loaded World Composition tiles')
streaming.enabled=true; streaming.location={X=-750000,Y=-750000,Z=0}
keys.F6(); drain(); counted=false
for _,message in ipairs(logs) do if message:find('World Composition tiles loaded: 1 of 3; streaming pinned at (-750000,-750000)',1,true) then counted=true end end
assert(counted,'F6 says where streaming is pinned')
print('PASS remote lab site engine: the site by level, the streaming pin and its fallback, ground, flush, autosave, the F6 tile count')
