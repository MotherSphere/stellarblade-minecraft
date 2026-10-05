package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Export=pcall(require,'export_test')
assert(loaded,'The bounded export comparison is missing')
local directory=os.tmpname(); os.remove(directory); assert(os.execute('mkdir '..string.format('%q',directory)))
local function unhex(value) return value:gsub('..',function(byte)return string.char(tonumber(byte,16))end) end
local png=unhex('89504e470d0a1a0a0000000d4948445200000500000002d00806000000cf7ddd5600000e0c4944415478daedc18100000000c3a0f9535fe1005501')
    ..string.rep('\0',3572)..unhex('af0146180001850592430000000049454e44ae426082')
local function write(name,data) local f=assert(io.open(directory..'/'..name,'wb')); assert(f:write(data)); assert(f:close()) end
local function read(name) local f=io.open(directory..'/'..name,'rb'); if not f then return nil end; local value=f:read('*a'); f:close(); return value end
local function setup()
    local b={actors=0,targets=0,watches=0,spawned=0,captures=0,cameras=0,time=10,context=1,logs={}}
    function b.camera() b.cameras=b.cameras+1; return {pawn_id=1,world_id=b.context} end
    function b.spawn() b.actors=b.actors+1; b.spawned=b.spawned+1; return {} end
    function b.configure(_,_,mode) assert(mode.width==1280 and mode.height==720 and mode.source==2 and mode.format==2) end
    function b.create_target() b.targets=b.targets+1; return {} end
    function b.bind(actor,target) b.actor,b.target=actor,target end
    function b.capture(actor) assert(actor==b.actor); b.captures=b.captures+1 end
    function b.export(actor,target,dir,name)
        assert(actor==b.actor and target==b.target and dir==directory)
        write(name,b.bad_baseline and 'invalid' or png)
    end
    function b.prepare_async(handle,done) assert(handle.actor==b.actor and handle.target==b.target); b.watches=b.watches+1; b.done=done end
    function b.export_async(handle,path)
        assert(handle.actor==b.actor and handle.target==b.target and path==directory..'/eve-exporttest-async.png')
        if b.inline_done~=nil then b.done(b.inline_done) end
        if b.throw_queue then error('submission outcome unknown') end
        write('eve-exporttest-async.png',b.partial and png:sub(1,60) or png)
    end
    function b.unwatch() if b.fail_unwatch then error('unwatch failed') end; if b.watches>0 then b.watches=b.watches-1 end end
    function b.destroy() if b.fail_destroy then error('destroy failed') end; b.actors=b.actors-1 end
    function b.release() assert(b.actors==0); b.targets=b.targets-1 end
    local api=Export.new(b,directory,function(message) b.logs[#b.logs+1]=message; if b.fail_log then error('logger failed') end end,function()return b.time end)
    return b,api
end
local count=0
local function check(name,run) run(); count=count+1; print('PASS '..name) end
write('eve-frame.txt','untouched production descriptor')
check('one target compares both exports without touching the paired wire',function()
    local b,api=setup(); assert(api.start()); assert(api.status()=='pending')
    assert(b.actors==1 and b.targets==1 and b.watches==1 and b.captures==2 and b.cameras==1)
    assert(not api.start() and b.spawned==1)
    assert(read('eve-frame.txt')=='untouched production descriptor')
    assert(read('eve-exporttest-kismet.png')==png and read('eve-exporttest-async.png')==png)
    assert(api.tick() and b.targets==1,'Finished PNG bytes alone must not release the target')
    b.done(true); assert(b.targets==1 and b.watches==1,'The completion callback must not clean up inline')
    assert(api.tick()); assert(api.status()=='idle' and b.actors==0 and b.targets==0 and b.watches==0)
    local logs=table.concat(b.logs,'\n')
    for _,stage in ipairs({'Kismet export start','Kismet export end','async export start','async export end','async callback success','async PNG validated'}) do
        assert(logs:find(stage,1,true),'missing measurement '..stage)
    end
end)
check('timeout and F11-style cancellation retain pending ownership until late callback',function()
    local b,api=setup(); b.partial=true; assert(api.start()); b.time=21
    assert(not api.tick() and api.status()=='cleanup-required')
    assert(not api.clear() and not api.start() and b.spawned==1 and b.actors==1 and b.targets==1 and b.watches==1)
    b.done(true); assert(api.tick()); assert(api.status()=='idle' and b.targets==0 and b.watches==0)
end)
check('failed completion stays owned until explicit cleanup and distinguishes false from absence',function()
    local b,api=setup(); assert(api.start()); b.done(false)
    assert(not api.tick() and b.targets==1 and b.watches==1 and not api.start())
    assert(api.clear() and b.targets==0 and b.watches==0)
end)
check('unknown submission outcome never releases a possibly queued target',function()
    local b,api=setup(); b.throw_queue=true
    assert(not api.start()); assert(not api.clear() and b.targets==1 and b.watches==1)
    b.done(false); assert(api.clear() and b.targets==0 and b.watches==0)
    b,api=setup(); b.inline_done=true; b.throw_queue=true
    assert(not api.start() and b.targets==1,'Even an inline completion must defer cleanup until a later tick')
    assert(api.tick() and b.targets==0)
end)
check('pre-submission failure and cleanup failure retain retryable ownership',function()
    local b,api=setup(); b.bad_baseline=true; b.fail_destroy=true
    assert(not api.start() and api.status()=='cleanup-required' and b.targets==1)
    assert(not api.start() and b.spawned==1)
    b.fail_destroy=false; assert(api.clear() and b.targets==0)
    b,api=setup(); assert(api.start()); b.done(true); b.fail_unwatch=true
    assert(not api.tick() and b.targets==1 and b.watches==1)
    b.fail_unwatch=false; assert(api.clear() and b.targets==0 and b.watches==0)
end)
check('bad completed PNG and context loss cannot queue another export',function()
    local b,api=setup(); b.partial=true; assert(api.start()); b.done(true)
    assert(not api.tick() and b.targets==1 and not api.start()); assert(api.clear())
    b,api=setup(); b.fail_log=true; assert(api.start()); b.context=2
    assert(not api.tick() and b.targets==1); b.done(true); assert(api.tick() and b.targets==0)
end)
for _,name in ipairs({'eve-exporttest-kismet.png','eve-exporttest-async.png','eve-frame.txt'}) do os.remove(directory..'/'..name) end
assert(os.execute('rmdir '..string.format('%q',directory)))
print(count..' bounded async export lifecycle checks passed; native queue/delegate compatibility remains untested.')
