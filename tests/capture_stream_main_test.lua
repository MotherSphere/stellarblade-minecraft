-- Exercise the real command and scheduling integration without invoking Unreal.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local queued,keys={},{}
local loop,console,frame_hook
local frames,stills,clears,returns=0,0,0,0
local state='idle'
local tick_error,schedule_error,clear_error,read_error=false,false,false,false
local report_error=false
local logs={}
local print_message=print
print=function(message)
    if report_error and message:find('Native live capture stopped',1,true) then error('report failed') end
    logs[#logs+1]=message
    print_message(message)
end
local capture={status=function()return 'idle' end}
function capture.frame() frames=frames+1; if frame_hook then frame_hook() end; return true,'frame' end
function capture.capture() stills=stills+1; return true,'still' end
function capture.cleanup() return true,'cleaned' end
function capture.clear() clears=clears+1; if clear_error then error('native cleanup failed') end; return true,'cleared' end
local bridge={status=function()return state end,shapes=function()return {} end}
function bridge.read() if read_error then error('bridge read failed') end; return {} end
function bridge.create() state='ready'; return true,'created' end
function bridge.enter() state='entered'; return true,'entered' end
function bridge.restore() returns=returns+1; state='idle'; return true,'returned' end
function bridge.tick() if tick_error then error('bridge failed') end end
function bridge.pose()
    if state~='entered' then return nil end
    return {session=string.rep('a',32),ack=1,state=state,position={x=0,y=0,z=0}}
end
local world={IsValid=function()return true end,GetAddress=function()return 12 end,GetFullName=function()return 'TestWorld' end}
local pawn={IsValid=function()return true end,GetWorld=function()return world end,GetAddress=function()return 11 end,
    GetFullName=function()return 'TestPawn' end,K2_GetActorLocation=function()return {X=10,Y=20,Z=300} end,
    K2_GetActorRotation=function()return {Pitch=0,Yaw=0,Roll=0} end,
    CapsuleComponent={IsValid=function()return true end,GetScaledCapsuleHalfHeight=function()return 95 end,GetScaledCapsuleRadius=function()return 35 end}}
local timing_available,frame_samples,time_samples=true,0,0
local system={IsValid=function()return true end,LineTraceSingle=function()return false end}
function system:GetFrameCount(...) assert(select('#',...)==0); frame_samples=frame_samples+1; return 1200 end
local gameplay={IsValid=function()return true end}
function gameplay:GetRealTimeSeconds(...) assert(select('#',...)==1 and (...)==world); time_samples=time_samples+1; return 42.5 end
StaticFindObject=function(path)
    if timing_available then
        if path=='/Script/Engine.Default__KismetSystemLibrary' then return system end
        if path=='/Script/Engine.Default__GameplayStatics' then return gameplay end
    end
    return {IsValid=function()return false end}
end
package.loaded.UEHelpers={GetPlayer=function()return pawn end,GetKismetSystemLibrary=function()return system end}
local ordinary_creates,probe_returns,probe_state,return_error=0,0,'idle',false
local diagnostic={status=function()return probe_state end,shapes=function()return {} end}
function diagnostic.create() ordinary_creates=ordinary_creates+1; return true,'diagnostic created' end
function diagnostic.restore() probe_returns=probe_returns+1; if return_error then error('return threw') end; return false,'No fixture is active' end
package.loaded.probe={new=function()return diagnostic end}
package.loaded.bridge_config={scale=100,directory='/unused'}
package.loaded.terrain={new=function()return {} end}
package.loaded.bridge={new=function()return bridge end}
package.loaded.capture={new=function()return capture end}
package.loaded.capture_adapter={new=function()return {} end}
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
    if schedule_error then error('schedule failed') end
    queued[#queued+1]=callback
end
LoopAsync=function(milliseconds,callback) assert(milliseconds==200); loop=callback end
dofile('stellar/Mods/StellarCollisionProbe/Scripts/main.lua')
local function drain() while #queued>0 do table.remove(queued,1)() end end
local function command(name) console(nil,{name}); drain() end
assert(keys['Shift+F7'],'The explicit Shift+F7 texture binding is missing')
assert(keys['Shift+F8'],'The explicit Shift+F8 native scene binding is missing')
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
keys.F4(); keys.F2(); drain(); command('capture')
assert(frames==0 and stills==0,'All three still export commands must reject native visual mode')
command('visuals'); assert(not scene_enabled); keys.F1(); drain()
loop(); drain(); assert(frames==1,'F1 should enable the entered link')
keys.F4(); keys.F2(); drain(); command('capture')
assert(frames==1 and stills==0,'Manual captures must not overlap live capture')
frame_hook=function() loop(); assert(#queued==0,'The pending gate was released during native capture') end
loop(); loop(); assert(#queued==1,'The bridge cannot queue catch-up callbacks')
drain(); frame_hook=nil; assert(frames==2)
keys.F1(); drain(); loop(); drain(); assert(frames==2,'F1 should stop capture')
command('live'); tick_error=true; loop(); drain(); tick_error=false
loop(); drain(); assert(frames==2,'Bridge failures must disable capture')
command('live'); tick_error=true; report_error=true; loop()
local ran=pcall(drain)
report_error=false; tick_error=false
assert(ran,'Unexpected cleanup reporting failure must not escape the game-thread callback')
loop(); assert(#queued==1,'An unexpected callback failure must release the pending gate'); drain()
assert(frames==2,'A callback failure must cancel capture')
command('live'); read_error=true; loop(); drain(); read_error=false
loop(); drain(); assert(frames==2,'A bridge read exception must cancel capture')
command('live'); schedule_error=true; loop(); schedule_error=false
local before=clears; loop(); drain()
assert(frames==2 and clears==before+1,'Schedule failure must cancel and defer cleanup to the game thread')
command('live'); clear_error=true; keys.F11(); drain()
assert(returns==1,'F11 must attempt the native return even when capture cleanup throws')
clear_error=false; loop(); drain(); assert(frames==2)
command('mirror'); command('enter'); command('live'); command('liveoff'); loop(); drain()
assert(frames==2,'Explicit liveoff must prevent subsequent captures')
command('frame'); command('capture'); command('depth')
assert(frames==3 and stills==2,'Still export commands must remain available after native visuals are disabled')
print('PASS live/texture commands, F11 cleanup independence, native F6 counters, and pending gate through capture')
