package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Permit=require('control_permit')
local directory=os.tmpname(); os.remove(directory); assert(os.execute('mkdir '..directory))
local now=100
local permit=Permit.new(directory,function()return now end)
local binding={run=string.rep('a',32),session=string.rep('b',32)}
local context={pawn=11,world=12}
local function read(slot)
    local f=io.open(directory..'/eve-control-permit.'..slot,'rb')
    if not f then return nil end
    local text=f:read('*a'); f:close(); return text
end
assert(permit.tick(nil,nil) and not read(0) and not read(1),'Idle startup must publish nothing')
assert(permit.tick(binding,context))
local first=assert(read(1))
assert(first=='SBMC_CONTROL 1\nRUN '..binding.run..'\nSESSION '..binding.session..'\nGEN 1\nSEQ 1\nTIME 100\nSTATE active\nCONTEXT 11 12\nEND 1\n')
assert(permit.tick(binding,context) and read(0):find('SEQ 2\n',1,true))
assert(read(1)==first,'Publishing a new slot must not remove the previous complete permit')
assert(permit.stop() and read(1):find('SEQ 3\nTIME 100\nSTATE stopped',1,true))
local stopped=read(1); assert(permit.stop() and read(1)==stopped,'Repeated idle cleanup must not fabricate activity')
assert(permit.tick(binding,context) and read(0):find('SEQ 4\n',1,true),'A resumed context is a new permit; the adapter requires a new neutral epoch')
assert(read(0):find('GEN 2\n',1,true),'Even a consumer that missed stopped must observe revocation in the next active permit')
assert(permit.tick(binding,{pawn=21,world=22}))
assert(permit.tick(binding,context))
assert(read(0):find('GEN 4\n',1,true),'A context A/B/A round trip must remain visible to a consumer that missed B')
now=99
assert(not permit.tick(binding,context),'Clock rollback must revoke rather than refresh active input')
assert(not read(0) and not read(1),'Invalid context/clock must remove both cached permits')
now=101
assert(permit.tick(binding,context))
assert(permit.tick(binding,nil) and (read(0) or read(1)):find('STATE',1,true))
assert(not permit.tick({run='invalid',session=binding.session},context))
assert(not read(0) and not read(1))
assert(permit.tick(binding,context))
local rename=os.rename
os.rename=function() return nil,'rename failed' end
local ok=permit.tick(binding,context)
os.rename=rename
assert(not ok and not read(0) and not read(1),'Publication errors must revoke every valid slot')
assert(permit.tick(binding,context),'Publication failure may recover only through a fresh receiver handshake')
assert(permit.stop())
-- A native-style reader may keep the old destination open through publication.
-- The selected-Proton CRT fixture separately proves delete+rename fails in this state.
assert(permit.tick(binding,context))
local renamed,held,held_bytes,retired_path=false,nil,nil,nil
local original_rename,original_remove=os.rename,os.remove
local slot_pattern='^'..directory..'/eve%-control%-permit%.[01]$'
os.remove=function(path)
    assert(not path:match(slot_pattern),'Move the old permit aside; deleting its held pathname makes Windows rename fail')
    return original_remove(path)
end
os.rename=function(from,to)
    if from:match(slot_pattern) then
        held=assert(io.open(from,'rb'));held_bytes=held:read('*a');assert(held:seek('set',0))
        assert(to:match('%.retired%.'..binding.run..'%.%d+$'),'Retired names need a never-reused run/sequence identity')
        retired_path=to;renamed=true
    end
    return original_rename(from,to)
end
local published,publication_error=permit.tick(binding,context)
os.rename,os.remove=original_rename,original_remove
assert(published,publication_error)
assert(renamed and held:read('*a')==held_bytes,'An open reader must retain the complete old permit')
assert(held:close());assert(not io.open(retired_path,'rb'),'Successful publication must retire the old path')
local latest=(tonumber(read(0):match('SEQ (%d+)'))>tonumber(read(1):match('SEQ (%d+)'))) and read(0) or read(1)
assert(latest~=held_bytes and latest:find('STATE active',1,true),'New readers must obtain a complete new permit')
-- Unknown rename failures still revoke, never silently defer or refresh the old generation.
local failed_generation=tonumber(latest:match('GEN (%d+)'))
os.rename=function(from,to)
    if from:match(slot_pattern) then return nil,'sharing denied',13 end
    return original_rename(from,to)
end
assert(not permit.tick(binding,context))
os.rename=original_rename
assert(not read(0) and not read(1),'Unexpected rename-aside failure must revoke both permits')
assert(permit.tick(binding,context))
assert(tonumber((read(0) or read(1)):match('GEN (%d+)'))>failed_generation,'Recovery must invalidate the previous input epoch')
-- A failure after moving the old slot must remove every file it still owns.
assert(permit.tick(binding,context))
os.rename=function(from,to)
    if from:sub(-4)=='.tmp' then return nil,'publication denied',13 end
    return original_rename(from,to)
end
assert(not permit.tick(binding,context))
os.rename=original_rename
assert(not read(0) and not read(1))
for _,slot in ipairs({0,1}) do assert(not io.open(directory..'/eve-control-permit.'..slot..'.tmp','rb')) end
-- Failed temporary cleanup cannot be reported as a successful idle stop.
assert(permit.tick(binding,context))
os.rename=function()return nil,'publication denied',13 end
os.remove=function(path)
    if path:sub(-4)=='.tmp' then return nil,'temporary cleanup denied',13 end
    return original_remove(path)
end
assert(not permit.tick(binding,context))
assert(not permit.stop(),'Unresolved temporary cleanup must remain a retryable failure')
os.rename,os.remove=original_rename,original_remove
assert(permit.stop())
for _,slot in ipairs({0,1}) do assert(not io.open(directory..'/eve-control-permit.'..slot..'.tmp','rb')) end
assert(permit.tick(binding,context))
-- Failed retired-file cleanup stays owned, blocks later publication, and retries safely.
assert(permit.tick(binding,context))
os.remove=function(path)
    if path:find('.retired.',1,true) then retired_path=path;return nil,'cleanup denied',13 end
    return original_remove(path)
end
assert(not permit.tick(binding,context),'Retirement failure must not claim successful publication')
assert(not read(0) and not read(1))
local retained=assert(io.open(retired_path,'rb'));assert(retained:close())
assert(not permit.tick(binding,context),'Unresolved retirement must not create more files or revive input')
os.remove=original_remove
assert(permit.tick(binding,context),'Cleanup recovery must use a fresh generation')
assert(not io.open(retired_path,'rb'),'Owned retired files must be removed after recovery')
assert(permit.stop())
for _,suffix in ipairs({'0','1','0.tmp','1.tmp'}) do os.remove(directory..'/eve-control-permit.'..suffix) end
assert(os.execute('rmdir '..directory))
print('Control permit tests passed: neutral startup, alternating slots, stop, invalid context and publication failure')
