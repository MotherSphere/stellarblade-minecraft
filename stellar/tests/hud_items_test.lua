package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Hud=require('hud_items')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function read(name) local f=io.open(directory..'/'..name,'rb'); if not f then return nil end; local t=f:read('a'); f:close(); return t end
local f=assert(io.open('protocol/fixtures/hud.txt','rb')); local fixture=f:read('a'); f:close()

assert(Hud.kind('Grenade')=='shock' and Hud.kind('PluseGrenade')=='pulse' and Hud.kind(nil)=='none' and Hud.kind('Foo')=='other')
assert(Hud.format(string.rep('a',32),7,1760000000000,{heal={kind='tumbler',count=3,max=5,usable=true},
    util={kind='shock',count=2,cooldown=0.4,usable=true},ammo={alias='Bullet_Normal',count=120,cooldown=0},dash=0.25,uses={1,0,0,0}})==fixture,
    'the text Java parses')
local clamped=Hud.format(string.rep('a',32),1,0,{util={kind='mine',count=12000,cooldown=-0.0,usable=false},dash=0/0,uses={-1,0,0,3e9}})
assert(clamped:find('\nHEAL none\nUTIL mine 9999 0.00 0\nAMMO none\nDASH 0.00\nUSES 0 0 0 2147483647\n',1,true),'Out-of-range values are clamped: '..clamped)

local SESSION=string.rep('b',32)
local state,failure,consume
local engine={hud=function() if failure then error(failure,0) end; return state end,watch_consume=function(fn) consume=fn end}
local wall,logged,place=1760000000,{},nil
local hud=Hud.new(engine,directory,function() return wall end,function(line) logged[#logged+1]=line end,function() return place end)
local function logs(text) local n=0; for _,line in ipairs(logged) do if line:find(text,1,true) then n=n+1 end end; return n end
local function slot(thumb,count) return {thumb=thumb,count=count,max=0,cooldown=0,usable=true} end
local function newest() -- The newer slot's text, by SEQ.
    local best,text
    for i=0,1 do
        local t=read('eve-hud.'..i)
        local seq=t and tonumber(t:match('\nSEQ (%d+)\n'))
        if seq and (not best or seq>best) then best,text=seq,t end
    end
    return text,best
end
local function second() wall=wall+1; hud.tick(); return newest() end
local function stable() hud.tick(); return second() end -- Two agreeing reads.
state={heal={thumb='Icon_PotionR',count=3,max=5,cooldown=0,usable=true},util=slot('Grenade',2),bullet='Bullet_Normal',
    ammo={count=120,cooldown=0},dash=0.25,search={ms=1.5,missing='lower',retry=2}}
hud.tick()
assert(read('eve-hud.0')==nil and read('eve-hud.1')==nil,'Without a link, no file is written')
place={session=SESSION}
hud.tick()
local text=read('eve-hud.1')
assert(text and read('eve-hud.0')==nil and text==string.format('SBMC_HUD 1\nSESSION %s\nSEQ 1\nTIME %d\nHEAL tumbler 3 5 1\nUTIL shock 2 0.00 1\n'..
    'AMMO Bullet_Normal 120 0.00\nDASH 0.25\nUSES 0 0 0 0\nEND 1\n',SESSION,wall*1000),'The first tick writes slot 1 with SEQ 1: '..tostring(text))
assert(logs('widget search 1.5 ms, missing lower, next in 2 s')==1,'The first search is logged')
assert(logs('heal Icon_PotionR 3/5 use true; util Grenade 2 cool 0 use true; lower - -; bullet Bullet_Normal 120; dash 0.25')==1,logged[#logged])
state.search={ms=2,missing='lower',retry=4}
hud.tick()
assert(read('eve-hud.0')==nil,'Same state in the same second: no new SEQ')
assert(logs('widget search')==1,'A fast search after the first is not logged')
local _,seq=second()
assert(seq==2 and read('eve-hud.0'):find('\nSEQ 2\n',1,true),'The next second publishes SEQ 2 in slot 0')
state.search={ms=7.25,missing='none'}
hud.tick()
assert(logs('widget search 7.2 ms, missing none')==1,'A slow search is logged')
state.search=nil
-- Uses, once a second read agrees: a drop of the same item; a selection from the last unit; a refill.
state.util=slot('Grenade',1); hud.tick()
text,seq=newest()
assert(seq==3 and text:find('\nUTIL shock 1 ',1,true) and text:find('\nUSES 0 0 0 0\n',1,true),'One read of a drop is no use yet')
hud.tick(); text,seq=newest()
assert(seq==4 and text:find('\nUSES 1 0 0 0\n',1,true) and logs('HUD: shock used (2 -> 1)')==1,'The agreeing read counts it, published at once')
state.util=slot('TrapGrenade',2); text=stable()
assert(text:find('\nUTIL mine 2 ',1,true) and text:find('\nUSES 1 0 0 0\n',1,true),'A selection is not a use')
assert(logs('util slot changed from Grenade at 1 to TrapGrenade (a last use is not counted)')==1)
state.util=slot('TrapGrenade',1); text=stable()
assert(text:find('\nUSES 1 0 1 0\n',1,true))
state.util=slot('TrapGrenade',2); text=stable()
assert(text:find('\nUSES 1 0 1 0\n',1,true),'A refill is not a use')
state.util=slot('HealGrenade',3); stable(); state.util=slot('HealGrenade',2); text=stable()
state.util=slot('Icon_PotionR',3); stable(); state.util=slot('Icon_PotionR',2); text=stable()
assert(text:find('\nUTIL tumbler 2 ',1,true) and text:find('\nUSES 1 0 1 0\n',1,true),'Field and tumbler drops are not combat uses')
-- A selection read between Stellar Blade's count and thumbnail writes: the new count under the old item.
state.util=slot('Grenade',3); stable(); state.util=slot('Grenade',1); second(); state.util=slot('TrapGrenade',1); text=stable()
assert(text:find('\nUTIL mine 1 ',1,true) and text:find('\nUSES 1 0 1 0\n',1,true),'A read no other agrees with is not a drop')
-- A new util widget (a level load) starts again: its first values are no drop from the old one's.
state.found_util=true; state.util=slot('TrapGrenade',0); hud.tick(); state.found_util=nil; text=second()
assert(text:find('\nUTIL mine 0 ',1,true) and text:find('\nUSES 1 0 1 0\n',1,true),'A new widget is no use')
-- Leaving the link forgets the util slot: a lower count after it is not a use.
state.util=slot('Grenade',3); stable()
place=nil; wall=wall+1; hud.tick(); place={session=SESSION}
state.util=slot('Grenade',2); text=stable()
assert(text:find('\nUSES 1 0 1 0\n',1,true),'A drop across a link loss is not counted')
-- A failing read: nothing published, one line for the same message.
_,seq=newest()
failure='widget gone'
local before=logs('read failed: widget gone')
wall=wall+1; hud.tick(); wall=wall+1; hud.tick()
assert(select(2,newest())==seq and before==0 and logs('read failed: widget gone')==1,'A failed read publishes nothing and logs once')
failure=nil
-- What the file cannot carry: no thumbnail, unreadable counts, a bullet alias out of grammar.
state={heal={count=3},util={thumb='Grenade'},bullet='Bullet Normal',ammo={count=1},dash='n/a'}
text=second()
assert(text:find('\nHEAL none\nUTIL none\nAMMO none\nDASH none\n',1,true),text)
-- A drop adds its size; a selection to a lower count is none; unchanged values are logged once.
state={util=slot('Grenade',3)}; stable(); state.util=slot('Grenade',1); text=stable()
assert(text:find('\nUSES 3 0 1 0\n',1,true),'A drop of two is two uses: '..text)
state.util=slot('FlashGrenade',0); text=stable(); second(); second()
assert(text:find('\nUSES 3 0 1 0\n',1,true),'A selection to a lower count is not a use')
assert(logs('; util FlashGrenade 0 cool 0 use true;')==1,'Unchanged values are logged once')
-- Ammo is logged where it appears, not at each shot; read lines have their own budget, so they cannot hide a use.
local lines=#logged
state.bullet,state.ammo='Bullet_Normal',{count=120,cooldown=0}; second()
for n=119,100,-1 do state.ammo.count=n; second() end
assert(#logged==lines+1 and logged[#logged]:find('; bullet Bullet_Normal 120;',1,true),'One line for the ammo')
for n=1,200 do state.heal=slot('Icon_PotionR',n%2); hud.tick() end
state.util=slot('FlashGrenade',2); stable(); state.util=slot('FlashGrenade',1); text=stable()
assert(text:find('\nUSES 3 0 1 1\n',1,true) and logs('HUD: sonic used (2 -> 1)')==1 and logs('HUD: heal ')==150,'Read lines stop at 150, uses still log')
-- The consume probe: the first five requests are logged.
for _=1,6 do consume() end
assert(logs('consume request seen')==5 and logs('consume request seen (5)')==1)
os.remove(directory..'/eve-hud.0'); os.remove(directory..'/eve-hud.1'); os.remove(directory)
print('PASS HUD items: kinds, shared fixture text, slot publication, use counting, failing reads and the consume probe')
