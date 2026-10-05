-- A native controller permit, independent of image payload completion.
local Permit={}
local MAX=9007199254740991
local function identity(value) return type(value)=='string' and #value==32 and value:match('^[0-9a-f]+$') end
local function integer(value) return type(value)=='number' and value>=0 and value<=MAX and value%1==0 end
function Permit.new(directory,clock)
    clock=clock or os.time
    local api={}
    local sequence,generation,last,last_time=0,1,nil,nil
    local function path(slot) return directory..'/eve-control-permit.'..slot end
    function api.revoke()
        last=nil
        generation=math.min(MAX,generation+1)
        for slot=0,1 do os.remove(path(slot)); os.remove(path(slot)..'.tmp') end
        return true
    end
    local function publish(binding,context,state)
        local now=clock()
        assert(integer(now) and (not last_time or now>=last_time),'Invalid or regressed input permit clock.')
        assert(identity(binding.run) and identity(binding.session),'Invalid input permit identity.')
        assert(context and integer(context.pawn) and context.pawn>0 and integer(context.world) and context.world>0,
            'Invalid native input context.')
        assert(sequence<MAX and generation<MAX,'Input permit counters exhausted.')
        sequence=sequence+1
        local packet=string.format('SBMC_CONTROL 1\nRUN %s\nSESSION %s\nGEN %.0f\nSEQ %.0f\nTIME %.0f\nSTATE %s\nCONTEXT %.0f %.0f\nEND %.0f\n',
            binding.run,binding.session,generation,sequence,now,state,context.pawn,context.world,sequence)
        local target=path(sequence%2)
        local file=assert(io.open(target..'.tmp','wb'))
        local written,why=file:write(packet)
        local closed,close_error=file:close()
        assert(written and closed,why or close_error or 'Input permit write failed.')
        -- Keep the other complete slot while replacing this slot with the Windows CRT.
        os.remove(target)
        assert(os.rename(target..'.tmp',target))
        last_time=now
        last=state=='active' and {run=binding.run,session=binding.session,pawn=context.pawn,world=context.world} or nil
    end
    local function attempt(binding,context,state)
        local ok,why=pcall(publish,binding,context,state)
        if not ok then api.revoke(); return false,'Input permit revoked: '..tostring(why) end
        return true
    end
    function api.stop()
        if not last then return true end
        generation=math.min(MAX,generation+1)
        return attempt(last,last,'stopped')
    end
    function api.tick(binding,context)
        if not binding or not context then return api.stop() end
        if last and (last.run~=binding.run or last.session~=binding.session
            or last.pawn~=context.pawn or last.world~=context.world) then
            generation=math.min(MAX,generation+1)
        end
        return attempt(binding,context,'active')
    end
    return api
end
return Permit
