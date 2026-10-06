-- Minecraft's combat verdicts for EVE (eve-combat.txt, SBMC_COMBAT 1), answered by Stellar Blade itself:
-- * a perfect dodge plays EVE's own perfect-dodge Show as the game does (its flip or spin, time scale, effects and
--   sounds, and its Effect keys: invulnerability while it plays, Burst gauge, the just-evade counter); if no new evade
--   effect is on EVE DODGE_CHECK seconds later, Lua slows Stellar Blade's world for the moment instead and plays the
--   evade effects;
-- * a guard or a perfect parry plays her guarded-hit Show, a perfect parry also its parry effect;
-- * a landed hit makes her react toward the attack: three variants of her stock light-hit Show take turns until one
--   animates her (a full-body or additive custom-animation alpha above zero REACTION_SAMPLE seconds later, from none
--   before), which is then kept; a try while she was already animating or moving tells nothing and is not counted.
-- Every in-memory change (a reaction's lifted gate or held tag, the time dilation) is undone on time, retried every
-- frame until it is.
-- Game-thread only; call tick each native frame. Events count only for the entered link's session.
local Combat={}
local SLOW,SLOW_SECONDS=0.3,0.5
local FRESH_MS=2500 -- The wall clock here has whole seconds.
local DODGE_CHECK=0.2
local REACTION_GAP,REACTION_SAMPLE,REACTION_RESTORE=0.6,0.3,1.1
local VARIANTS,PROBES={'library','gate','tag'},12 -- Reactions tried before giving up when none animates EVE.
local SHOW='/Game/Art/Show/'
local JUST_EVADE=SHOW..'CH_P_EVE_01/Skill/P_Eve_Sword_Normal_JustEvade1.P_Eve_Sword_Normal_JustEvade1'
local MOVING=10 -- cm/s: EVE moving cancels a hit reaction.
local GUARDED=SHOW..'Result/Result_Guard.Result_Guard'
local FX='/Game/Art/FX/Char/PC/Skill/'
local EVADE_EFFECTS={FX..'Evade/NS_A_EvadeBody_01.NS_A_EvadeBody_01',FX..'Evade/NS_A_EvadeParticle_02.NS_A_EvadeParticle_02'}
local PARRY_EFFECT=FX..'Parry/NS_JustParry_02.NS_JustParry_02'
local EVENTS={perfect_dodge=true,perfect_parry=true,guard=true,hit=true}
function Combat.parse(text)
    if type(text)~='string' or #text>512 then return nil end
    local session,seq,time,event,yaw,ending=text:match('^SBMC_COMBAT 1\nSESSION (%x+)\nSEQ (%d+)\nTIME (%d+)\nEVENT ([%a_]+) (%S+)\nEND (%d+)\n$')
    if not session or #session~=32 or session:find('[^0-9a-f]') or seq~=ending or not EVENTS[event] then return nil end
    local angle
    if yaw~='none' then
        angle=tonumber(yaw)
        if not angle or angle~=angle or math.abs(angle)>180 then return nil end
    end
    return {session=session,seq=tonumber(seq),time=tonumber(time),event=event,yaw=angle}
end
-- wall: seconds since the Unix epoch; real: monotonic seconds; link: the entered link ({session=...}) or nil.
function Combat.new(engine,directory,wall,real,log,link)
    local api={}
    local session,last=nil,0
    local slow_until,restore_failed
    local dodge -- A perfect-dodge Show whose effect is still to be checked.
    local reactions,last_reaction,pending,chosen=0,-math.huge,nil,nil
    local lines=0
    local function note(message)
        if lines<200 then lines=lines+1; log('Combat: '..message) end
    end
    local function read()
        local f=io.open(directory..'/eve-combat.txt','rb')
        if not f then return nil end
        local text=f:read(513); f:close()
        return text
    end
    local function play(paths)
        local played={}
        for _,path in ipairs(paths) do
            local ok,why=pcall(engine.play_effect,path)
            played[#played+1]=path:match('[^.]+$')..(ok and '' or ' failed: '..tostring(why))
        end
        return table.concat(played,', ')
    end
    local function show(path,yaw)
        local ok,info=pcall(engine.play_show,path,yaw)
        return ok and 'show '..info or 'show failed: '..tostring(info),ok
    end
    local function evade_bodies()
        local ok,labels=pcall(engine.eve_effects)
        local count=0
        for _,label in ipairs(ok and labels or {}) do if label:find('NS_A_EvadeBody_01',1,true)==1 then count=count+1 end end
        return count,ok,labels
    end
    -- Stellar Blade's world slowed for the moment of the dodge; skipped when the game already runs dilated.
    local function slow()
        if slow_until then slow_until=real()+SLOW_SECONDS; return 'slow motion extended' end
        local ok,previous=pcall(engine.time_dilation,SLOW)
        if ok and math.abs(previous-1)<0.01 then slow_until=real()+SLOW_SECONDS; return string.format('time at %.2f',SLOW) end
        if ok then pcall(engine.time_dilation,previous); return string.format('Stellar Blade owns time (%.2f)',previous) end
        return 'time dilation failed: '..tostring(previous)
    end
    function api.tick()
        local place=link()
        -- Until time is back to normal, every frame retries: Stellar Blade must never stay slowed.
        if slow_until and (not place or real()>=slow_until) then
            local ok,why=pcall(engine.time_dilation,1)
            if ok then slow_until,restore_failed=nil,nil; note('time restored')
            elseif not restore_failed then restore_failed=true; note('time dilation not restored yet, retrying: '..tostring(why)) end
        end
        if pending and not pending.sampled and real()>=pending.at+REACTION_SAMPLE then
            pending.sampled=true
            local ok,line,custom,additive,speed=pcall(engine.hit_sample,pending.sample)
            local busy=type(pending.sample)=='table' and pending.sample.busy
            local animated=ok and not busy and ((custom or 0)>0.01 or (additive or 0)>0.01)
            local moved=ok and not animated and (speed or 0)>MOVING
            local verdict=''
            if animated and not chosen then chosen=pending.method; verdict='; reaction kept'
            elseif not chosen and (busy or moved) then reactions=reactions-1; verdict=busy and '; inconclusive, EVE was already animating' or '; inconclusive, EVE was moving'
            elseif not chosen and reactions>=PROBES then verdict='; no variant animated EVE, reactions off' end
            note(string.format('hit %d %s: %s%s',pending.seq,pending.method,ok and line or 'sample failed: '..tostring(line),verdict))
        end
        -- Every frame until it works: a lifted gate or a held tag must never stay.
        if pending and real()>=pending.at+REACTION_RESTORE then
            local ok,why=pcall(engine.hit_restore,pending.sample)
            if ok then pending=nil
            elseif not pending.restore_failed then
                pending.restore_failed=true
                note(string.format('hit %d %s: restore failed, retrying: %s',pending.seq,pending.method,tostring(why)))
            end
        end
        if dodge and real()>=dodge.at+DODGE_CHECK then
            local count,ok,labels=evade_bodies()
            if dodge.played and count>dodge.before then note(string.format('perfect_dodge %d: Stellar Blade plays it',dodge.seq))
            elseif place then note(string.format('perfect_dodge %d: show not seen%s; %s; effects %s',dodge.seq,
                ok and '' or ' ('..tostring(labels)..')',slow(),play(EVADE_EFFECTS))) end
            dodge=nil
        end
        if not place then return end
        local event=Combat.parse(read())
        if not event or event.session~=place.session then return end
        if event.session~=session then session,last=event.session,0 end
        if event.seq<=last then return end
        last=event.seq
        if math.abs(wall()*1000-event.time)>FRESH_MS then return end
        local answer=''
        if event.event=='perfect_dodge' then
            local before=evade_bodies()
            local info,played=show(JUST_EVADE,event.yaw)
            answer='; '..info
            dodge={at=real(),seq=event.seq,before=before,played=played}
        elseif event.event=='perfect_parry' then
            answer='; '..show(GUARDED,event.yaw)..'; effects '..play({PARRY_EFFECT})
        elseif event.event=='guard' then
            answer='; '..show(GUARDED,event.yaw)
        elseif event.event=='hit' and event.yaw and not pending and (chosen or reactions<PROBES) and real()-last_reaction>=REACTION_GAP then
            reactions=reactions+1
            last_reaction=real()
            local method=chosen or VARIANTS[(reactions-1)%#VARIANTS+1]
            local ok,info,sample=pcall(engine.hit_reaction,event.yaw,method)
            answer='; reaction '..(ok and info or method..' failed: '..tostring(info))
                ..(not ok and not chosen and reactions==PROBES and '; no variant animated EVE, reactions off' or '')
            if ok then pending={at=real(),sample=sample,seq=event.seq,method=method} end
        end
        note(string.format('%s %d%s%s',event.event,event.seq,event.yaw and string.format(' from %.0f deg',event.yaw) or '',answer))
    end
    return api
end
return Combat
