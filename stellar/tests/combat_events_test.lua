package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Combat=require('combat_events')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local f=assert(io.open('protocol/fixtures/combat-event.txt','rb')); local fixture=f:read('a'); f:close()

-- The shared fixture parses; a malformed or unknown event does not.
local parsed=Combat.parse(fixture)
assert(parsed and parsed.session==string.rep('a',32) and parsed.seq==7 and parsed.time==1760000000000 and parsed.event=='hit' and parsed.yaw==-135)
assert(Combat.parse(fixture:gsub('hit %-135.0','perfect_dodge none')).yaw==nil)
for _,bad in ipairs({fixture:gsub('hit','dodge'),fixture:gsub('%-135.0','200'),fixture:gsub('END 7','END 8'),fixture:gsub('SESSION a','SESSION A'),fixture:sub(1,-2)}) do
    assert(Combat.parse(bad)==nil,bad)
end

local SESSION=string.rep('b',32)
local wall,real=1791300000,100.0
local dilation,dilations,effects,shows,logged=1,{},{},{},{}
local on_eve={} -- Effect labels on EVE's mesh.
local takes=true -- Whether Stellar Blade takes a played Show (its evade effect appears on EVE).
local reactions,restores,alphas,busy,speed={},0,{},false,0
local engine={}
function engine.time_dilation(value) local previous=dilation; dilation=value; dilations[#dilations+1]=value; return previous end
function engine.play_effect(path) effects[#effects+1]=path:match('[^.]+$') end
function engine.play_show(path,yaw)
    shows[#shows+1]=path:match('[^.]+$')
    if takes and path:find('JustEvade',1,true) then on_eve[#on_eve+1]='NS_A_EvadeBody_01[NiagaraComponent]@CharacterMesh0:None' end
    return path:match('[^.]+$')..' toward '..tostring(yaw)
end
function engine.eve_effects() return on_eve end
function engine.hit_reaction(yaw,method) reactions[#reactions+1]=method; return method..' toward '..yaw,{method=method,busy=busy} end
function engine.hit_sample(sample) local a=alphas[sample.method] or 0; return string.format('alpha %.2f',a),a,0,speed end
function engine.hit_restore() restores=restores+1 end
local place={session=SESSION}
local combat=Combat.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
local seq=0
local function event(name,time,session)
    seq=seq+1
    write('eve-combat.txt',string.format('SBMC_COMBAT 1\nSESSION %s\nSEQ %d\nTIME %d\nEVENT %s 90.0\nEND %d\n',session or SESSION,seq,time or wall*1000,name,seq))
end
local function last() return logged[#logged] end

combat.tick()
assert(#dilations==0 and #logged==0,'No event file, nothing to do')
-- A perfect dodge plays EVE's own Show; when its effect shows up on her, nothing else is needed.
event('perfect_dodge'); combat.tick(); combat.tick()
assert(shows[1]=='P_Eve_Sword_Normal_JustEvade1' and #dilations==0,table.concat(logged,'\n'))
real=real+0.2; combat.tick()
assert(last():find('Stellar Blade plays it',1,true) and #dilations==0 and #effects==0,last())
-- Without a new one (an earlier evade effect still on EVE does not count), Lua slows Stellar Blade's world for the
-- moment and plays the evade effects.
takes=false
event('perfect_dodge'); combat.tick(); real=real+0.2; combat.tick()
assert(dilation==0.3 and effects[1]=='NS_A_EvadeBody_01' and effects[2]=='NS_A_EvadeParticle_02' and last():find('show not seen',1,true),last())
real=real+0.3; combat.tick()
assert(dilation==0.3,'Still slowed for the moment of the dodge')
real=real+0.3; combat.tick()
assert(dilation==1 and last()=='Combat: time restored','Time comes back after half a second')
-- Guards and perfect parries play the guarded-hit Show; a parry also its effect.
event('perfect_parry'); combat.tick()
assert(shows[#shows]=='Result_Guard' and effects[3]=='NS_JustParry_02' and dilation==1)
event('guard'); combat.tick()
assert(shows[#shows]=='Result_Guard' and #effects==3)
event('perfect_dodge',(wall-10)*1000); combat.tick()
assert(shows[#shows]=='Result_Guard','A stale event is ignored')
event('perfect_dodge',nil,string.rep('c',32)); combat.tick()
assert(shows[#shows]=='Result_Guard','Another session is ignored')
-- Stellar Blade's own slow motion is left alone.
event('perfect_dodge'); combat.tick(); dilation=0.5; real=real+0.2; combat.tick()
assert(dilation==0.5 and last():find('Stellar Blade owns time',1,true),last())
dilation=1
-- A failed restore is retried until it works, and leaving the link restores time as soon as it can.
event('perfect_dodge'); combat.tick(); real=real+0.2; combat.tick()
local set=engine.time_dilation
engine.time_dilation=function() error('no controller') end
place=nil; combat.tick(); combat.tick()
assert(dilation==0.3 and last():find('retrying',1,true),'A failed restore is retried, logged once')
engine.time_dilation=set; combat.tick()
assert(dilation==1 and last()=='Combat: time restored',last())
place={session=SESSION}
-- A dodge whose link is gone before its check neither slows time nor plays effects.
local played=#effects
event('perfect_dodge'); combat.tick(); place=nil; real=real+0.2; combat.tick()
assert(dilation==1 and #effects==played,'No fallback once the link is gone')
place={session=SESSION}
takes=true
-- Hits: the variants take turns until one animates EVE, which is then kept; each one is undone after it.
real=real+1; event('hit'); combat.tick()
assert(reactions[1]=='gate' and last():find('reaction gate toward 90',1,true),last())
real=real+0.3; combat.tick()
assert(last():find('alpha 0.00',1,true) and not last():find('kept',1,true),last())
real=real+0.4; event('hit'); combat.tick()
assert(#reactions==1,'One reaction at a time, even past the gap')
real=real+0.4; combat.tick()
assert(restores==1,'The variant is undone after it plays')
alphas.library=0.8
event('hit'); combat.tick()
assert(reactions[2]=='library')
real=real+0.3; combat.tick()
assert(last():find('reaction kept',1,true),last())
real=real+0.8; combat.tick()
real=real+1; event('hit'); combat.tick()
assert(reactions[3]=='library','The animating variant is kept')
real=real+1.1; combat.tick()
-- A failed undo is retried every frame.
local undo=engine.hit_restore
engine.hit_restore=function() error('map gone') end
real=real+1; event('hit'); combat.tick(); real=real+1.2; combat.tick(); combat.tick()
assert(last():find('restore failed, retrying',1,true),last())
engine.hit_restore=undo; combat.tick()
assert(restores==4,'The undo succeeds once it can')
-- A try while EVE already animates or moves tells nothing: not counted, the same variant comes back.
local function probe_combat() return Combat.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end) end
alphas,reactions,busy={library=1},{},true
local probe=probe_combat()
real=real+2; event('hit'); probe.tick(); real=real+0.3; probe.tick()
assert(last():find('inconclusive, EVE was already animating',1,true) and not last():find('kept',1,true),last())
busy,speed=false,250; real=real+1.2; probe.tick()
real=real+1; event('hit'); probe.tick(); real=real+0.3; probe.tick()
assert(reactions[2]=='gate' and last():find('inconclusive, EVE was moving',1,true),last())
speed=0
-- Twelve reactions that never animate EVE turn reactions off.
alphas,reactions={},{}
probe=probe_combat()
for _=1,13 do real=real+2; event('hit'); probe.tick(); real=real+0.3; probe.tick(); real=real+1.1; probe.tick() end
assert(#reactions==12 and table.concat(logged,'\n'):find('no variant animated EVE, reactions off',1,true),'Reactions stop after twelve that never animate EVE')
os.remove(directory..'/eve-combat.txt'); os.remove(directory)
print('Combat events: parse, session binding, staleness, perfect-dodge Show with its fallback, guard Show, reaction variants and their undo')
