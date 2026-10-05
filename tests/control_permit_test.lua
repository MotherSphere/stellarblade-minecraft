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
for _,suffix in ipairs({'0','1','0.tmp','1.tmp'}) do os.remove(directory..'/eve-control-permit.'..suffix) end
assert(os.execute('rmdir '..directory))
print('Control permit tests passed: neutral startup, alternating slots, stop, invalid context and publication failure')
