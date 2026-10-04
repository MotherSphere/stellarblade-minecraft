-- Exercise the real command and scheduling integration without invoking Unreal.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local queued,keys={},{}
local loop,console,frame_hook
local frames,stills,clears,returns=0,0,0,0
local state='idle'
local tick_error,schedule_error,clear_error,read_error=false,false,false,false
local report_error=false
local print_message=print
print=function(message)
    if report_error and message:find('Native live capture stopped',1,true) then error('report failed') end
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
local world={IsValid=function()return true end}
local pawn={IsValid=function()return true end,GetWorld=function()return world end}
package.loaded.UEHelpers={GetPlayer=function()return pawn end,GetKismetSystemLibrary=function()return {} end}
package.loaded.probe={new=function()return {status=function()return 'idle' end} end}
package.loaded.bridge_config={scale=100,directory='/unused'}
package.loaded.terrain={new=function()return {} end}
package.loaded.bridge={new=function()return bridge end}
package.loaded.capture={new=function()return capture end}
package.loaded.capture_adapter={new=function()return {} end}
Key={F1='F1',F2='F2',F4='F4',F5='F5',F6='F6',F7='F7',F8='F8',F9='F9',F11='F11'}
RegisterKeyBind=function(key,callback) keys[key]=callback end
RegisterConsoleCommandHandler=function(name,callback) assert(name=='sbmc'); console=callback end
ExecuteInGameThread=function(callback)
    if schedule_error then error('schedule failed') end
    queued[#queued+1]=callback
end
LoopAsync=function(milliseconds,callback) assert(milliseconds==200); loop=callback end
dofile('stellar/Mods/StellarCollisionProbe/Scripts/main.lua')
local function drain() while #queued>0 do table.remove(queued,1)() end end
local function command(name) console(nil,{name}); drain() end
assert(keys.F1,'F1 live capture toggle is missing')
loop(); drain(); assert(frames==0,'Startup must not capture')
command('live'); loop(); drain(); assert(frames==0,'Unentered scene must not capture')
command('mirror'); command('enter'); keys.F1(); drain()
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
print('PASS live commands, F11 despite cleanup failure, and pending gate through capture')
