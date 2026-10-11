package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Usmap=require('usmap')

local function setup(files)
    local state={ready=false,files=files,dumps=0,listings=0,logged={},clock=10}
    local engine={
        ready=function() if state.ready=='error' then error('no player',0) end; return state.ready end,
        files=function()
            state.listings=state.listings+1
            if state.files=='error' then error('no IterateGameDirectories',0) end
            return state.files
        end,
        dump=function()
            state.dumps=state.dumps+1
            if state.dump_error then error(state.dump_error,0) end
            state.clock=state.clock+1.5
            if state.writes then state.files[#state.files+1]=state.writes end
        end}
    local usmap=Usmap.new(engine,function() return state.clock end,function(line) state.logged[#state.logged+1]=line end)
    return usmap,state
end

-- Missing: one dump once EVE is loaded, never before and never again.
local usmap,state=setup({'UE4SS.dll','UE4SS-settings.ini'})
state.writes='SB-4.26.2-0+++UE4+Release-4.26-08a0733.usmap'
usmap.tick(); usmap.tick()
assert(state.dumps==0 and state.listings==0 and #state.logged==0,'Nothing before EVE is loaded')
state.ready='error'
usmap.tick()
assert(state.dumps==0 and #state.logged==0,'A failed readiness check waits')
state.ready=true
usmap.tick()
assert(state.dumps==1,'Missing: dumped')
assert(state.logged[1]=='Mappings: wrote SB-4.26.2-0+++UE4+Release-4.26-08a0733.usmap for the avatar pack builder in 1.5 s',state.logged[1])
usmap.tick(); usmap.tick()
assert(state.dumps==1 and state.listings==2 and #state.logged==1,'Once per Lua lifetime')

-- Present, under either name and any case: no dump.
for _,name in ipairs({'Mappings.usmap','SB-4.26.2.USMAP'}) do
    usmap,state=setup({'UE4SS.dll',name})
    state.ready=true
    usmap.tick(); usmap.tick()
    assert(state.dumps==0 and state.logged[1]=='Mappings: '..name..' is present; no dump' and #state.logged==1,name)
end

-- A dump that fails, or writes nothing, is logged once with the key to press; never retried.
usmap,state=setup({})
state.ready,state.dump_error=true,'attempt to call a nil value (global DumpUSMAP)'
usmap.tick(); usmap.tick()
assert(state.dumps==1 and #state.logged==1 and state.logged[1]:find('DumpUSMAP failed: attempt to call',1,true)
    and state.logged[1]:find('Ctrl+Numpad6',1,true),state.logged[1])
usmap,state=setup({'UE4SS.dll'})
state.ready=true
usmap.tick()
assert(state.dumps==1 and state.logged[1]:find('no .usmap is in UE4SS',1,true) and state.logged[1]:find('Ctrl+Numpad6',1,true),state.logged[1])

-- Folders that cannot be listed: no dump, since the file may already be there.
usmap,state=setup('error')
state.ready=true
usmap.tick(); usmap.tick()
assert(state.dumps==0 and #state.logged==1 and state.logged[1]=='Mappings: UE4SS folders not listed, no dump: no IterateGameDirectories',state.logged[1])
print('usmap ok')
