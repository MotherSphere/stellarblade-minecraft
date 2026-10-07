package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Combat=require('combat_events')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local f=assert(io.open('protocol/fixtures/combat-event.txt','rb')); local fixture=f:read('a'); f:close()

-- The shared fixture parses; a malformed or unknown event does not.
local parsed=Combat.parse(fixture)
assert(parsed and parsed.session==string.rep('a',32) and parsed.seq==7 and parsed.time==1760000000000 and parsed.event=='hit' and parsed.yaw==-135)
assert(Combat.parse(fixture:gsub('hit %-135.0','perfect_dodge none')).yaw==nil)
assert(Combat.parse(fixture:gsub('hit','guard_strong')).event=='guard_strong' and Combat.parse(fixture:gsub('hit','parry')).event=='parry')
for _,bad in ipairs({fixture:gsub('SBMC_COMBAT 2','SBMC_COMBAT 1'),fixture:gsub('hit','dodge'),fixture:gsub('%-135.0','200'),fixture:gsub('END 7','END 8'),fixture:gsub('SESSION a','SESSION A'),fixture:sub(1,-2)}) do
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
local lifted,unlift=0,function() end -- Keys whose stop-at-move is lifted; the restore the fake hands out.
local gates,failing={},{} -- The hit-level gate each play lifted (restored within the call); Shows that fail.
function engine.play_show(path,yaw,held,gate)
    if failing[path:match('[^.]+$')] then error('not loaded',0) end
    shows[#shows+1]=path:match('[^.]+$')
    gates[#shows]=gate or false
    if takes and path:find('JustEvade',1,true) then on_eve[#on_eve+1]='NS_A_EvadeBody_01[NiagaraComponent]@CharacterMesh0:None' end
    if held then lifted=lifted+1 end
    return path:match('[^.]+$')..' toward '..tostring(yaw),held and function() unlift(); lifted=lifted-1 end or nil
end
function engine.anim_state() return 'custom alpha 1.00' end
function engine.eve_effects() return on_eve end
function engine.hit_reaction(yaw,method) reactions[#reactions+1]=method; return method..' toward '..yaw,{method=method,busy=busy} end
function engine.hit_sample(sample) local a=alphas[sample.method] or 0; return string.format('alpha %.2f',a),a,0,speed end
function engine.hit_restore() restores=restores+1 end
local place={session=SESSION}
local combat=Combat.new(engine,directory,function() return wall end,function() return real end,function(line) logged[#logged+1]=line end,function() return place end)
local seq=0
local function event(name,time,session)
    seq=seq+1
    write('eve-combat.txt',string.format('SBMC_COMBAT 2\nSESSION %s\nSEQ %d\nTIME %d\nEVENT %s 90.0\nEND %d\n',session or SESSION,seq,time or wall*1000,name,seq))
end
local function last() return logged[#logged] end

combat.tick()
assert(#dilations==0 and #logged==0,'No event file, nothing to do')
-- A perfect dodge plays EVE's own Show; when its effect shows up on her, nothing else is needed.
event('perfect_dodge'); combat.tick(); combat.tick()
assert(shows[1]=='P_Eve_Sword_Normal_JustEvade1' and #dilations==0,table.concat(logged,'\n'))
assert(lifted==1 and logged[#logged]:find('before: custom alpha 1.00',1,true),'The flip plays with its stop-at-move lifted')
real=real+0.2; combat.tick()
assert(last():find('Stellar Blade plays it; +0.2s: custom alpha',1,true) and #dilations==0 and #effects==0,last())
assert(lifted==0,'The lifted stop-at-move is restored once the key has started')
real=real+0.4; combat.tick()
assert(last():find('+0.6s: custom alpha',1,true),last())
-- A failed restore of the lifted key is retried every frame.
unlift=function() error('key gone') end
event('perfect_dodge'); combat.tick(); real=real+0.2; combat.tick(); combat.tick()
assert(lifted==1 and table.concat(logged,'\n'):find('lifted Show key not restored yet, retrying',1,true))
unlift=function() end; combat.tick()
assert(lifted==0,'Restored once it can be')
real=real+1; combat.tick()
-- Two perfect dodges 0.15 s apart lift the same key: it comes back 0.2 s after the second, not after the first.
local restored_at
unlift=function() restored_at=real end
event('perfect_dodge'); combat.tick()
real=real+0.15; event('perfect_dodge'); combat.tick()
real=real+0.1; combat.tick()
assert(restored_at==nil,'Not 0.2 s after the first dodge while the second one still needs it')
real=real+0.11; combat.tick() -- Past 0.2 s after the second play (float sums).
assert(restored_at==real,'0.2 s after the second')
unlift,lifted=function() end,0
real=real+1; combat.tick()
-- Without a new one (an earlier evade effect still on EVE does not count), Lua slows Stellar Blade's world for the
-- moment and plays the evade effects.
takes=false
event('perfect_dodge'); combat.tick(); real=real+0.2; combat.tick()
assert(dilation==0.3 and effects[1]=='NS_A_EvadeBody_01' and effects[2]=='NS_A_EvadeParticle_02' and last():find('show not seen',1,true),last())
real=real+0.3; combat.tick()
assert(dilation==0.3,'Still slowed for the moment of the dodge')
real=real+0.3; combat.tick()
assert(dilation==1 and logged[#logged-1]=='Combat: time restored' and last():find('+0.6s',1,true),'Time comes back after half a second')
-- A perfect parry plays Stellar Blade's just parry on EVE: her result Show and the attacker's effects Show, whose
-- sparks Lua plays itself only when that Show fails; nothing is left lifted.
event('perfect_parry'); combat.tick()
assert(shows[#shows-1]=='P_Eve_Sword_Normal_Guard1_JustParry' and shows[#shows]=='Result_JustParry_FX' and #effects==2 and dilation==1,last())
real=real+0.6; combat.tick()
assert(last():find('perfect_parry '..seq..' +0.6s: custom alpha',1,true),last())
failing.Result_JustParry_FX=true
event('perfect_parry'); combat.tick()
assert(effects[3]=='NS_JustParry_02' and last():find('show failed: not loaded; effects NS_JustParry_02',1,true),last())
failing={}
-- A parry plays the parried-hit Show; a guard the guarded-hit Show for its strength, its body key's hit-level check lifted.
event('parry'); combat.tick()
assert(shows[#shows]=='Result_Parry_Back' and not gates[#shows] and #effects==3)
event('guard'); combat.tick()
assert(shows[#shows]=='Result_Guard_Back' and gates[#shows]=='SBShowAnimKey_0')
event('guard_strong'); combat.tick()
assert(shows[#shows]=='Result_Guard_Back_Strong' and gates[#shows]=='SBShowAnimKey_0' and lifted==0)
event('perfect_dodge',(wall-10)*1000); combat.tick()
assert(shows[#shows]=='Result_Guard_Back_Strong','A stale event is ignored')
event('perfect_dodge',nil,string.rep('c',32)); combat.tick()
assert(shows[#shows]=='Result_Guard_Back_Strong','Another session is ignored')
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
-- An event file of another protocol version is refused and reported once.
local versioned=probe_combat()
write('eve-combat.txt','SBMC_COMBAT 1\nSESSION '..SESSION..'\nSEQ 99\nTIME '..(wall*1000)..'\nEVENT hit 90.0\nEND 99\n')
versioned.tick(); versioned.tick()
local reported=0
for _,line in ipairs(logged) do if line:find('unsupported event version 1',1,true) then reported=reported+1 end end
assert(reported==1,'A version mismatch is reported once')
os.remove(directory..'/eve-combat.txt'); os.remove(directory)
print('Combat events: parse, session binding, staleness, perfect-dodge Show with its fallback, parry and guard Shows, reaction variants and their undo')
