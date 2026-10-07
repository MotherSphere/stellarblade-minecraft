-- Stellar Blade's HUD item slots for Minecraft (eve-hud.0/.1, SBMC_HUD 1), as Stellar Blade's own HUD widgets show them
-- (engine.hud): the heal and util slots, EVE's selected drone bullet with its ammo, the dash attack's cooldown, and the
-- uses of each combat consumable since this Lua loaded. A util count that drops while its item (the slot's thumbnail
-- texture) stays the same is that many uses; a rise (a refill) or another item (a selection) is none, so Stellar Blade
-- stays authoritative for selection, counts, cooldowns and conditions. A read counts once the next one agrees: Stellar
-- Blade writes the count and the thumbnail apart (the brush may load later). Published while a link is entered, when the
-- content changes and at least every wall second, in alternating slots (SEQ % 2). Game-thread only; call tick on the
-- 200 ms bridge timer.
local Hud={}
local LINES,READS=200,150 -- Read-value lines have their own budget, so a value that keeps changing cannot hide a use.
-- ItemTable.Asset2D, the thumbnail texture's name, to the item's kind.
Hud.KINDS={Icon_PotionR='tumbler',Icon_Potion='potion',Icon_PotionSmall='lingering',HealGrenade='field',Grenade='shock',
    PluseGrenade='pulse',TrapGrenade='mine',FlashGrenade='sonic',WbPump='pump'}
Hud.USES={'shock','pulse','mine','sonic'}
function Hud.kind(thumb)
    if thumb==nil or thumb=='' then return 'none' end
    return Hud.KINDS[thumb] or 'other'
end
local function whole(n,max) n=tonumber(n); return math.floor(n and n>0 and math.min(n,max) or 0) end -- NaN and -0 give 0.
local function share(c) c=tonumber(c); return string.format('%.2f',c and c>0 and math.min(c,1) or 0) end
-- state: {heal={kind=,count=,max=,usable=}, util={kind=,count=,cooldown=,usable=}, ammo={alias=,count=,cooldown=},
-- dash=cooldown, uses={shock,pulse,mine,sonic}}; a missing entry is none.
function Hud.format(session,seq,time,state)
    local h,u,a,n=state.heal,state.util,state.ammo,state.uses
    return string.format('SBMC_HUD 1\nSESSION %s\nSEQ %d\nTIME %d\n%s\n%s\n%s\n%s\nUSES %d %d %d %d\nEND %d\n',session,seq,time,
        h and string.format('HEAL %s %d %d %d',h.kind,whole(h.count,9999),whole(h.max,9999),h.usable==false and 0 or 1) or 'HEAL none',
        u and string.format('UTIL %s %d %s %d',u.kind,whole(u.count,9999),share(u.cooldown),u.usable==false and 0 or 1) or 'UTIL none',
        a and string.format('AMMO %s %d %s',a.alias,whole(a.count,9999),share(a.cooldown)) or 'AMMO none',
        state.dash and 'DASH '..share(state.dash) or 'DASH none',
        whole(n[1],2147483647),whole(n[2],2147483647),whole(n[3],2147483647),whole(n[4],2147483647),seq)
end
-- engine: hud() and watch_consume(fn) (pose_adapter); wall: Unix seconds; link: the entered link ({session=}) or nil.
function Hud.new(engine,directory,wall,log,link)
    local api,lines,reads,seq,consumed,errors={},0,0,0,0,{}
    local uses={shock=0,pulse=0,mine=0,sonic=0}
    local util,pending -- The util slot at the last two agreeing reads with a link, {kind=,count=,thumb=}; the last read.
    local shown,searched -- The last logged read; whether a widget search was logged.
    local key,second -- The last publication's content (without SEQ and TIME) and wall second.
    local function note(message) if lines<LINES then lines=lines+1; log('HUD: '..message) end end
    local hooked,why=pcall(engine.watch_consume,function()
        consumed=consumed+1
        if consumed<=5 then note(string.format('consume request seen (%d)',consumed)) end
    end)
    if not hooked then note('consume request not watched: '..tostring(why)) end
    local function write(text)
        local temporary,slot=directory..'/eve-hud.tmp',directory..'/eve-hud.'..seq%2
        local file,err=io.open(temporary,'wb')
        assert(file,err)
        local written,write_error=file:write(text)
        local closed,close_error=file:close()
        assert(written,write_error); assert(closed,close_error)
        -- MSVC rename refuses an existing destination; readers keep the other slot.
        local exists=io.open(slot,'rb')
        if exists then exists:close(); assert(os.remove(slot)) end
        assert(os.rename(temporary,slot))
    end
    local function item(s) -- A slot shown with a known item and a readable count, else none.
        local kind=Hud.kind(s and s.thumb)
        if kind=='none' or type(s.count)~='number' then return nil end
        return {kind=kind,count=s.count,max=s.max,cooldown=s.cooldown,usable=s.usable}
    end
    local function v(x) return x==nil and '-' or math.type(x)=='float' and string.format('%.2f',x) or tostring(x) end
    function api.tick()
        local here=link()
        if not here then util,pending=nil,nil; return end
        local ok,read=pcall(engine.hud)
        if not ok then
            local message=tostring(read)
            if not errors[message] and lines<LINES then errors[message]=true; note('read failed: '..message) end
            return
        end
        local search=read.search
        if search and (not searched or search.ms>5) then
            searched=true
            note(string.format('widget search %.1f ms, missing %s%s',search.ms,search.missing,search.retry and ', next in '..search.retry..' s' or ''))
        end
        local s=read.util
        local kind,count=Hud.kind(s and s.thumb),s and tonumber(s.count)
        if read.found_util then util,pending=nil,nil end -- A new widget's values are no drop from the old one's.
        if pending and pending.kind==kind and pending.count==count then
            -- ponytail: widget polling; a last unit whose slot at once shows another item is not counted (logged instead),
            -- and a thumbnail that lags its count past a second read reads as a drop. Hook FSBItemBucketItemConsume::Simulate
            -- (0x141b0bd90) or walk the native item buckets if the grouped test shows misses.
            if util and kind~=util.kind then
                if util.count==1 then note(string.format('util slot changed from %s at 1 to %s (a last use is not counted)',v(util.thumb),v(s and s.thumb))) end
            elseif util and util.count and count and count<util.count then
                if uses[kind] then uses[kind]=uses[kind]+util.count-count end
                note(string.format('%s used (%s -> %s)',kind,v(util.count),v(count)))
            end
            util={kind=kind,count=count,thumb=s and s.thumb}
        end
        pending={kind=kind,count=count}
        local h,u,l,a=read.heal or {},s or {},read.lower or {},read.ammo or {}
        local values={v(h.thumb),v(h.count),v(h.max),v(h.usable),v(u.thumb),v(u.count),v(u.cooldown),v(u.usable),v(l.thumb),v(l.count),
            v(read.bullet),v(a.count),v(read.dash)}
        local line=string.format('heal %s %s/%s use %s; util %s %s cool %s use %s; lower %s %s; bullet %s %s; dash %s',table.unpack(values))
        -- A running cooldown is logged where it starts and ends, not at each step; ammo where it appears or goes, not at each shot.
        values[7],values[12],values[13]=tostring((tonumber(u.cooldown) or 0)>0),tostring(a.count~=nil),tostring((tonumber(read.dash) or 0)>0)
        local seen=table.concat(values,' ')
        if seen~=shown then
            shown=seen
            if reads<READS then reads=reads+1; log('HUD: '..line) end
        end
        local alias=read.bullet
        local state={heal=item(read.heal),util=item(s),dash=type(read.dash)=='number' and read.dash or nil,
            uses={uses.shock,uses.pulse,uses.mine,uses.sonic}}
        if read.ammo and type(read.ammo.count)=='number' and type(alias)=='string' and alias~='None' and #alias<=64 and alias:match('^[%w_]+$') then
            state.ammo={alias=alias,count=read.ammo.count,cooldown=read.ammo.cooldown}
        end
        local now,content=wall(),Hud.format(here.session,0,0,state)
        if content==key and now==second then return end
        seq=seq+1
        write(Hud.format(here.session,seq,now*1000,state))
        key,second=content,now
    end
    return api
end
return Hud
