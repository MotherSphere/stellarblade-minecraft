package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local Requests=require('requests')
local directory=os.tmpname();os.remove(directory);assert(os.execute('mkdir '..directory))
local function write(name,text) local f=assert(io.open(directory..'/'..name,'wb'));f:write(text);f:close() end
local function read(name) local f=io.open(directory..'/'..name,'rb'); if not f then return nil end; local t=f:read('a'); f:close(); return t end
local function fixture(name) local f=assert(io.open('protocol/fixtures/'..name,'rb')); local t=f:read('a'); f:close(); return t end
local request,ack=fixture('request.txt'),fixture('request-ack.txt')

-- The shared fixture parses and its answer encodes to the shared acknowledgement; malformed requests do not parse.
local parsed=Requests.parse(request)
assert(parsed and parsed.session==string.rep('a',32) and parsed.seq==4 and parsed.time==1760000000000 and parsed.action=='import')
assert(Requests.encode_ack(parsed,'ok','Imported 12 Minecraft collision boxes.')==ack)
assert(Requests.encode_ack(parsed,'failed','a\nb\240'..string.rep('x',500)):match('\nMESSAGE a%?b%?x+\nEND 4\n$'),'Messages stay one bounded ASCII line')
assert(#Requests.encode_ack(parsed,'failed',string.rep('x',500)):match('MESSAGE (x+)')==400)
assert(Requests.encode_ack(parsed,'ok',''):find('\nMESSAGE -\n',1,true))
for _,action in ipairs({'enter','return'}) do assert(Requests.parse((request:gsub('import',action))).action==action) end
for _,bad in ipairs({request:gsub('END 4','END 5'),request:gsub('import','teleport'),request:gsub('SBMC_REQUEST 1','SBMC_REQUEST 2'),
        request:gsub('SESSION a','SESSION A'),request:gsub('SESSION a','SESSION '),request:gsub('SEQ 4','SEQ 0'):gsub('END 4','END 0'),
        request:sub(1,-2),request..'\n',request:gsub('\n','\r\n'),request:gsub('ACTION import','ACTION import now'),
        request:gsub('SEQ 4','SEQ 1234567890123456'):gsub('END 4','END 1234567890123456'),request..string.rep('x',300)}) do
    assert(Requests.parse(bad)==nil,bad)
end
assert(Requests.parse(nil)==nil)

-- A fake engine: the link's and terrain's sessions, and the key actions run.
local A,B=string.rep('a',32),string.rep('b',32)
local wall=1760000000
local link,terrain=nil,A
local ran,results,logged={},{},{}
local offered_error=false
local requests=Requests.new(directory,function() return wall end,function(line) logged[#logged+1]=line end,
    function() return link end,
    function() if offered_error then error('terrain unreadable') end; return terrain end,
    function(name)
        ran[#ran+1]=name
        if name=='mirror' then link=A elseif name=='restore' then link=nil end
        return results[name]~=false,name..' done'
    end)
local seq=0
local function ask(action,session,time,number)
    seq=number or seq+1
    write('eve-request.txt',string.format('SBMC_REQUEST 1\nSESSION %s\nSEQ %d\nTIME %d\nACTION %s\nEND %d\n',
        session or A,seq,time or wall*1000,action,seq))
    requests.tick()
end
local function answer() local a=read('eve-request-ack.txt'); return a and a:match('\nRESULT (%l+)\nMESSAGE ([^\n]*)\n'),a end
local function result() return (answer()) end

requests.tick(); assert(#ran==0 and read('eve-request-ack.txt')==nil,'No request, nothing runs and nothing answers')
-- Enter needs a link of the request's session; refused without running.
ask('enter'); assert(#ran==0 and result()=='refused' and select(2,answer()):find('SEQ 1\nACTION enter\n',1,true))
assert(logged[#logged]=='Request: enter 1 refused: No Minecraft link to enter; import first.',logged[#logged])
-- Import before a link: only for the session terrain.txt names.
terrain=B; ask('import'); assert(#ran==0 and result()=='refused' and logged[#logged]:find('names another session',1,true))
terrain=nil; ask('import'); assert(#ran==0 and logged[#logged]:find('names no session',1,true))
offered_error=true; ask('import'); offered_error=false
assert(#ran==0 and result()=='refused' and logged[#logged]:find('session check failed: ',1,true),'A failed check refuses')
terrain=A; ask('import'); assert(ran[1]=='mirror' and result()=='ok' and logged[#logged]=='Request: import 5 from Minecraft')
local _,text=answer(); assert(text:find('MESSAGE mirror done\nEND 5\n',1,true))
-- The same request again, an older SEQ or a stale one never runs twice.
requests.tick(); requests.tick(); assert(#ran==1,'A request runs once')
ask('enter',A,nil,4); assert(#ran==1,'An older SEQ is ignored')
ask('enter',A,wall*1000-2501,7); assert(#ran==1,'Older than 2.5 s is ignored')
ask('enter',A,wall*1000+2501,8); assert(#ran==1,'Further than 2.5 s ahead is ignored')
ask('enter',A,wall*1000-2500,9); assert(ran[2]=='enter' and result()=='ok','A newer SEQ within 2.5 s runs')
ask('enter',A,wall*1000+2500); assert(ran[3]=='enter' and result()=='ok','Up to 2.5 s ahead runs')
-- Another session's request is refused while a link exists, import included.
ask('return',B,nil,1); assert(#ran==3 and result()=='refused' and logged[#logged]:find('Another Minecraft session is linked.',1,true))
terrain=B; ask('import',B); assert(#ran==3 and logged[#logged]:find('return EVE first',1,true))
terrain=A
-- A failing action answers failed with its message; a throwing one too.
results.restore=false; ask('return',A,nil,20); assert(ran[4]=='restore' and result()=='failed')
-- A failed return leaves no link (the bridge is stopping) but can be retried, like F11.
results.restore=nil; ask('return'); assert(ran[5]=='restore' and result()=='ok','A failed return can be retried')
link=A
local throwing=Requests.new(directory,function() return wall end,function() end,function() return A end,function() return A end,
    function() error('action threw') end)
seq=seq+1; write('eve-request.txt',string.format('SBMC_REQUEST 1\nSESSION %s\nSEQ %d\nTIME %d\nACTION enter\nEND %d\n',A,seq,wall*1000,seq))
throwing.tick(); assert(result()=='failed' and select(2,answer()):find('action threw',1,true))
-- An unwritable answer is logged, never thrown.
os.remove(directory..'/eve-request-ack.txt'); assert(os.execute('mkdir '..directory..'/eve-request-ack.tmp'))
ask('return'); assert(ran[6]=='restore' and logged[#logged]:find('Request: answer not written: ',1,true))
os.execute('rm -r '..directory)
print('PASS session requests: shared fixtures, freshness, SEQ, session gates, refusals and answers')
