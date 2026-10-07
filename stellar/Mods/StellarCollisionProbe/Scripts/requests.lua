-- Minecraft's session requests (eve-request.txt, SBMC_REQUEST 1): /stellar import, enter and return run the same actions
-- as F5, F8 and F11, without Stellar Blade's window. A request runs once: within 2.5 seconds of TIME, with a newer SEQ
-- when it comes from the session of the last request taken. Import needs the session terrain.txt names (and no other linked session); enter
-- and return need the link's own session; anything else is refused without running. Every request taken is answered in
-- eve-request-ack.txt (SBMC_REQUEST_ACK 1) with ok, failed or refused and the action's message. Game-thread only.
local Requests={}
local FRESH_MS,MESSAGE=2500,400
Requests.ACTIONS={import='mirror',enter='enter',['return']='restore'} -- The F5, F8 and F11 actions.
function Requests.parse(text)
    if type(text)~='string' or #text>256 then return nil end
    local session,seq,time,action,ending=text:match('^SBMC_REQUEST 1\nSESSION (%x+)\nSEQ (%d+)\nTIME (%d+)\nACTION (%l+)\nEND (%d+)\n$')
    if not session or #session~=32 or session:find('[^0-9a-f]') or seq~=ending or not Requests.ACTIONS[action]
        or #seq>15 or #time>16 or tonumber(seq)<1 then return nil end
    return {session=session,seq=tonumber(seq),time=tonumber(time),action=action}
end
function Requests.encode_ack(request,result,message)
    message=(tostring(message):gsub('[^\32-\126]','?')):sub(1,MESSAGE)
    return string.format('SBMC_REQUEST_ACK 1\nSESSION %s\nSEQ %d\nACTION %s\nRESULT %s\nMESSAGE %s\nEND %d\n',
        request.session,request.seq,request.action,result,message=='' and '-' or message,request.seq)
end
-- wall: Unix seconds; linked(): the session of the current Minecraft link, or nil; offered(): the session terrain.txt
-- names, or nil; run(name): runs a key action, returning success and its message.
function Requests.new(directory,wall,log,linked,offered,run)
    local api,lines,last={},0,nil
    local function note(message) if lines<100 then lines=lines+1; log('Request: '..message) end end
    local function read()
        local f=io.open(directory..'/eve-request.txt','rb')
        if not f then return nil end
        local text=f:read(257); f:close()
        return text
    end
    local function refusal(request)
        local link=linked()
        if request.action=='import' then
            local named=offered()
            if named~=request.session then return 'Minecraft terrain names '..(named and 'another session' or 'no session')..'.' end
            if link and link~=request.session then return 'Another Minecraft session is linked; return EVE first.' end
        elseif link~=request.session then
            return link and 'Another Minecraft session is linked.' or 'No Minecraft link to '..request.action..'; import first.'
        end
    end
    local function answer(request,result,message)
        local path=directory..'/eve-request-ack.txt'
        local ok,err=pcall(function()
            local file=assert(io.open(directory..'/eve-request-ack.tmp','wb'))
            local written,why=file:write(Requests.encode_ack(request,result,message))
            local closed,close_error=file:close()
            assert(written,why); assert(closed,close_error)
            -- MSVC rename refuses an existing destination.
            local exists=io.open(path,'rb')
            if exists then exists:close(); assert(os.remove(path)) end
            assert(os.rename(directory..'/eve-request-ack.tmp',path))
        end)
        if not ok then note('answer not written: '..tostring(err)) end
    end
    function api.tick()
        local request=Requests.parse(read())
        if not request or math.abs(wall()*1000-request.time)>FRESH_MS
            or last and last.session==request.session and request.seq<=last.seq then return end
        last=request
        local checked,why=pcall(refusal,request)
        if not checked or why then
            why=checked and why or 'session check failed: '..tostring(why)
            note(string.format('%s %d refused: %s',request.action,request.seq,why))
            return answer(request,'refused',why)
        end
        note(string.format('%s %d from Minecraft',request.action,request.seq))
        local ran,ok,message=pcall(run,Requests.ACTIONS[request.action])
        answer(request,ran and ok and 'ok' or 'failed',ran and message or ok)
    end
    return api
end
return Requests
