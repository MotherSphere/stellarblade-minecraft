-- The usmap the avatar pack builder needs: UE4SS's type mappings of this game build. Once per Lua lifetime, once EVE is
-- loaded in a world (so the level's types are loaded too), and only when UE4SS's folders hold no .usmap yet, Lua asks
-- UE4SS to write one with DumpUSMAP, the Lua twin of its Ctrl+Numpad6 key. The file's name and folder are UE4SS's:
-- Mappings.usmap, or <project>-<engine version>.usmap in newer builds, in its working folder (ue4ss/ beside the game
-- executable, or that folder itself in the old layout). The dump stalls the game once, for about a second. Game-thread
-- only; call tick on the 200 ms bridge timer.
local Usmap={}
local function usmap_in(names)
    for _,name in ipairs(names) do if type(name)=='string' and name:lower():match('%.usmap$') then return name end end
end
-- engine: ready() (EVE is loaded in a world), files() (the file names in UE4SS's folders), dump() (DumpUSMAP);
-- clock: seconds, for the log.
function Usmap.new(engine,clock,log)
    local done=false
    local api={}
    function api.tick()
        if done then return end
        local checked,ready=pcall(engine.ready)
        if not checked or not ready then return end
        done=true
        local listed,names=pcall(engine.files)
        if not listed then log('Mappings: UE4SS folders not listed, no dump: '..tostring(names)); return end
        local present=usmap_in(names)
        if present then log('Mappings: '..present..' is present; no dump'); return end
        local start=clock()
        local dumped,why=pcall(engine.dump)
        if not dumped then
            log('Mappings: DumpUSMAP failed: '..tostring(why)..'; press Ctrl+Numpad6 in game to write the usmap')
            return
        end
        local seconds=clock()-start
        local relisted,after=pcall(engine.files)
        local written=relisted and usmap_in(after)
        log(written and string.format('Mappings: wrote %s for the avatar pack builder in %.1f s',written,seconds)
            or string.format('Mappings: DumpUSMAP returned in %.1f s but no .usmap is in UE4SS\'s folders; press Ctrl+Numpad6 in game',seconds))
    end
    return api
end
return Usmap
