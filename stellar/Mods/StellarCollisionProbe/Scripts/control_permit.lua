-- A native controller permit, independent of image payload completion.
local Permit={}
local MAX=9007199254740991
local function identity(value) return type(value)=='string' and #value==32 and value:match('^[0-9a-f]+$') end
local function integer(value) return type(value)=='number' and value>=0 and value<=MAX and value%1==0 end
function Permit.new(directory,clock)
    clock=clock or os.time
    local api={}
    local sequence,generation,last,last_time=0,1,nil,nil
    local retired,cleanup_pending=nil,false
    local function path(slot) return directory..'/eve-control-permit.'..slot end
    local function remove_file(name)
        local ok,why,code=os.remove(name)
        return ok or code==2,why
    end
    local function cleanup_retired()
        if not retired then return true end
        local ok,why=remove_file(retired)
        if ok then retired=nil end
        return ok,why
    end
    local function cleanup_revoked()
        local failure
        for slot=0,1 do
            for _,name in ipairs({path(slot),path(slot)..'.tmp'}) do
                local ok,why=remove_file(name)
                if not ok then failure=why end
            end
        end
        local ok,why=cleanup_retired()
        if not ok then failure=why end
        cleanup_pending=failure~=nil
        return not cleanup_pending,failure
    end
    function api.revoke()
        last=nil
        generation=math.min(MAX,generation+1)
        return cleanup_revoked()
    end
    local function publish(binding,context,state)
        if cleanup_pending then assert(cleanup_revoked()) end
        assert(cleanup_retired())
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
        -- A deleted Windows pathname stays occupied while a reader holds it.
        -- Move it aside first; the other complete slot remains available.
        local old=target..'.retired.'..binding.run..'.'..string.format('%.0f',sequence)
        local moved,move_error,code=os.rename(target,old)
        assert(moved or code==2,move_error)
        if moved then retired=old end
        assert(os.rename(target..'.tmp',target))
        assert(cleanup_retired())
        last_time=now
        last=state=='active' and {run=binding.run,session=binding.session,pawn=context.pawn,world=context.world} or nil
    end
    local function attempt(binding,context,state)
        local ok,why=pcall(publish,binding,context,state)
        if not ok then
            local cleaned,cleanup_error=api.revoke()
            return false,'Input permit revoked: '..tostring(why)
                ..(cleaned and '' or '; cleanup pending: '..tostring(cleanup_error))
        end
        return true
    end
    function api.stop()
        if not last then
            if cleanup_pending then return cleanup_revoked() end
            return cleanup_retired()
        end
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
