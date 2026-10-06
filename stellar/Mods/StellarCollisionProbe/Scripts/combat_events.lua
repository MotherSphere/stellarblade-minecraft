-- Minecraft's combat verdicts for EVE (eve-combat.txt, SBMC_COMBAT 1), answered by Stellar Blade itself: a perfect
-- dodge slows Stellar Blade's world, EVE included, for the moment of the dodge (Minecraft's world stays slowed longer,
-- which is the advantage) and plays its evade effects on EVE; a perfect parry plays its parry effect; a hit makes EVE
-- react toward the attack, alternating her hit Show and her hit animation (both are being evaluated), each sampled
-- REACTION_SAMPLE seconds later. Game-thread only; call tick each native frame. Events count only for the entered
-- link's session.
local Combat={}
local SLOW,SLOW_SECONDS=0.3,0.5
local FRESH_MS=2500 -- The wall clock here has whole seconds.
local REACTION_GAP,REACTION_SAMPLE=0.6,0.3 -- Seconds between hit reactions; delay of their sample.
local FX='/Game/Art/FX/Char/PC/Skill/'
local EFFECTS={
    perfect_dodge={FX..'Evade/NS_A_EvadeBody_01.NS_A_EvadeBody_01',FX..'Evade/NS_A_EvadeParticle_02.NS_A_EvadeParticle_02'},
    perfect_parry={FX..'Parry/NS_JustParry_02.NS_JustParry_02'},
}
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
    local reactions,last_reaction,pending=0,-math.huge,nil
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
    local function play(event)
        local played={}
        for _,path in ipairs(EFFECTS[event] or {}) do
            local ok,why=pcall(engine.play_effect,path)
            played[#played+1]=path:match('[^.]+$')..(ok and '' or ' failed: '..tostring(why))
        end
        return table.concat(played,', ')
    end
    function api.tick()
        local place=link()
        -- Until time is back to normal, every frame retries: Stellar Blade must never stay slowed.
        if slow_until and (not place or real()>=slow_until) then
            local ok,why=pcall(engine.time_dilation,1)
            if ok then slow_until,restore_failed=nil,nil; note('time restored')
            elseif not restore_failed then restore_failed=true; note('time dilation not restored yet, retrying: '..tostring(why)) end
        end
        if pending and real()>=pending.at+REACTION_SAMPLE then
            local ok,line=pcall(engine.hit_sample,pending.sample)
            note(string.format('hit %d %s: %s',pending.seq,pending.method,ok and line or 'sample failed: '..tostring(line)))
            pending=nil
        end
        if not place then return end
        local event=Combat.parse(read())
        if not event or event.session~=place.session then return end
        if event.session~=session then session,last=event.session,0 end
        if event.seq<=last then return end
        last=event.seq
        if math.abs(wall()*1000-event.time)>FRESH_MS then return end
        local slowed=''
        if event.event=='perfect_dodge' then
            if slow_until then slow_until=real()+SLOW_SECONDS; slowed='; slow motion extended'
            else
                local ok,previous=pcall(engine.time_dilation,SLOW)
                if ok and math.abs(previous-1)<0.01 then slow_until=real()+SLOW_SECONDS; slowed=string.format('; time at %.2f',SLOW)
                elseif ok then pcall(engine.time_dilation,previous); slowed=string.format('; Stellar Blade owns time (%.2f)',previous)
                else slowed='; time dilation failed: '..tostring(previous) end
            end
        end
        local reaction=''
        if event.event=='hit' and event.yaw and real()-last_reaction>=REACTION_GAP then
            reactions=reactions+1
            last_reaction=real()
            local method=reactions%2==1 and 'show' or 'animation'
            local ok,info,sample=pcall(engine.hit_reaction,event.yaw,method)
            reaction='; reaction '..(ok and info or method..' failed: '..tostring(info))
            if ok then pending={at=real(),sample=sample,seq=event.seq,method=method} end
        end
        note(string.format('%s %d%s%s%s%s',event.event,event.seq,event.yaw and string.format(' from %.0f deg',event.yaw) or '',
            EFFECTS[event.event] and '; effects '..play(event.event) or '',slowed,reaction))
    end
    return api
end
return Combat
