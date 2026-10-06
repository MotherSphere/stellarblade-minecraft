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
local dilation,dilations,effects,logged=1,{},{},{}
local engine={}
function engine.time_dilation(value) local previous=dilation; dilation=value; dilations[#dilations+1]=value; return previous end
function engine.play_effect(path) effects[#effects+1]=path:match('[^.]+$') end
local reactions={}
function engine.hit_reaction(yaw,method) reactions[#reactions+1]=method; return method..' toward '..yaw,{moved=12.5} end
function engine.hit_sample(sample) return string.format('head moved %.1f cm',sample.moved) end
local place={session=SESSION}
local combat=Combat.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
local seq=0
local function event(name,time,session)
    seq=seq+1
    write('eve-combat.txt',string.format('SBMC_COMBAT 1\nSESSION %s\nSEQ %d\nTIME %d\nEVENT %s 90.0\nEND %d\n',session or SESSION,seq,time or wall*1000,name,seq))
end

combat.tick()
assert(#dilations==0 and #logged==0,'No event file, nothing to do')
event('perfect_dodge')
combat.tick(); combat.tick()
assert(dilation==0.3 and #dilations==1 and effects[1]=='NS_A_EvadeBody_01' and effects[2]=='NS_A_EvadeParticle_02',table.concat(logged,'\n'))
real=real+0.3; combat.tick()
assert(dilation==0.3,'Still slowed for the moment of the dodge')
real=real+0.3; combat.tick()
assert(dilation==1 and logged[#logged]=='Combat: time restored','Time comes back after half a second')
event('perfect_parry'); combat.tick()
assert(effects[3]=='NS_JustParry_02' and dilation==1,'A parry plays its effect without slowing time')
event('perfect_dodge',(wall-10)*1000); combat.tick()
assert(dilation==1,'A stale event is ignored')
event('perfect_dodge',nil,string.rep('c',32)); combat.tick()
assert(dilation==1,'Another session is ignored')
dilation=0.5
event('perfect_dodge'); combat.tick()
assert(dilation==0.5 and logged[#logged]:find('Stellar Blade owns time',1,true),'Stellar Blade\'s own slow motion is left alone')
dilation=1
event('perfect_dodge'); combat.tick()
local set=engine.time_dilation
engine.time_dilation=function() error('no controller') end
place=nil; combat.tick(); combat.tick()
assert(dilation==0.3 and logged[#logged]:find('retrying',1,true),'A failed restore is retried, logged once')
engine.time_dilation=set; combat.tick()
assert(dilation==1 and logged[#logged]=='Combat: time restored','Leaving the link restores time as soon as it can')
place={session=SESSION}
event('hit'); combat.tick()
assert(logged[#logged]=='Combat: hit '..seq..' from 90 deg; reaction show toward 90.0',logged[#logged])
real=real+0.3; combat.tick()
assert(logged[#logged]=='Combat: hit '..seq..' show: head moved 12.5 cm',logged[#logged])
event('hit'); combat.tick()
assert(#reactions==1 and not logged[#logged]:find('reaction',1,true),'Hits closer than the reaction gap react once')
real=real+0.6; event('hit'); combat.tick()
assert(reactions[2]=='animation','Reactions alternate between the Show and the animation')
os.remove(directory..'/eve-combat.txt'); os.remove(directory)
print('Combat events: parse, session binding, staleness, slow motion and its restoration, effects')
