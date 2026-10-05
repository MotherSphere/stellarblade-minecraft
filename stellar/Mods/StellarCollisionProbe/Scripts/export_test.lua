-- One target, one async submission, and two fixed diagnostic images. Game thread only.
local Capture=require('capture')
local Export={}
local mode={width=1280,height=720,source=2,format=2}
local baseline,asynchronous='eve-exporttest-kismet.png','eve-exporttest-async.png'
function Export.new(engine,directory,log,clock)
    clock=clock or os.time
    local owned
    local api={}
    local function say(message) if log then pcall(log,'Export test '..message) end end
    local function measured(name,run)
        say(name..' start')
        local ok,result=pcall(run)
        say(name..' end'..(ok and '' or ' failed'))
        if not ok then error(result,0) end
        return result
    end
    function api.status()
        if not owned then return 'idle' end
        return owned.submitted and owned.completed==nil and not owned.cancelled and 'pending' or 'cleanup-required'
    end
    local function cleanup()
        local handle=owned
        if not handle then return true,'Export test is idle.' end
        if handle.submitted and handle.completed==nil then
            return false,'Export test awaits async completion; its actor, target and hook remain owned.'
        end
        local ok,why=pcall(function()
            engine.unwatch(handle)
            if handle.actor then engine.destroy(handle.actor); handle.actor=nil end
            if handle.target then engine.release(handle.target); handle.target=nil end
        end)
        if not ok then return false,'Export test cleanup required: '..tostring(why) end
        owned=nil
        return true,'Export test capture resources released; diagnostic images retained.'
    end
    function api.clear()
        if owned then owned.cancelled='cancelled' end
        return cleanup()
    end
    function api.start()
        if owned then return false,'Finish or clean up the existing export test first.' end
        local ok,why=pcall(function()
            for _,name in ipairs({baseline,asynchronous}) do
                local removed,_,code=os.remove(directory..'/'..name)
                assert(removed or code==2,'Could not remove the preceding export test image.')
            end
            local camera=engine.camera()
            local handle={pawn_id=camera.pawn_id,world_id=camera.world_id}
            owned=handle
            handle.actor=assert(engine.spawn(camera),'Export test actor was not created.')
            engine.configure(handle.actor,camera,mode)
            handle.target=assert(engine.create_target(camera,mode),'Export test target was not created.')
            engine.bind(handle.actor,handle.target)
            engine.prepare_async(handle,function(success)
                if owned~=handle or not handle.submitted or handle.completed~=nil then return end
                handle.completed=success
                say('async callback '..(success and 'success' or 'failure'))
            end)
            engine.capture(handle.actor)
            measured('Kismet export',function() engine.export(handle.actor,handle.target,directory,baseline) end)
            Capture.validate_png(directory..'/'..baseline,mode.width,mode.height)
            -- Recapture the same camera/target in this same game-thread callback,
            -- so both exporters follow capture work rather than only the first.
            engine.capture(handle.actor)
            handle.started=clock()
            handle.submitted=true -- A thrown native call may already have queued work.
            measured('async export',function() engine.export_async(handle,directory..'/'..asynchronous) end)
        end)
        if not ok then
            if owned then owned.cancelled='submission failed' end
            if owned and owned.submitted then
                return false,tostring(why)..'; export test resources retained until a later completion tick.'
            end
            local _,message=cleanup()
            return false,tostring(why)..'; '..message
        end
        return true,'Export comparison submitted; waiting for its native completion callback.'
    end
    function api.tick()
        local handle=owned
        if not handle then return true end
        if handle.completed~=nil then
            if handle.cancelled then return cleanup() end
            if not handle.completed then return false,'Async export reported failure; run F11 to clean up the retained test.' end
            local ok,size=pcall(Capture.validate_png,directory..'/'..asynchronous,mode.width,mode.height)
            if not ok then return false,'Async export completed with an invalid PNG; run F11 to clean up. '..tostring(size) end
            say('async PNG validated: '..size..' bytes')
            return cleanup()
        end
        if not handle.submitted then return cleanup() end
        local now=clock()
        if now<handle.started or now-handle.started>=10 then handle.cancelled=handle.cancelled or 'completion deadline reached' end
        local ok,camera=pcall(engine.camera)
        if not ok or camera.pawn_id~=handle.pawn_id or camera.world_id~=handle.world_id then
            handle.cancelled=handle.cancelled or 'native context changed'
        end
        if handle.cancelled then return false,'Export test stopped: '..handle.cancelled..'; retaining resources until async completion.' end
        return true
    end
    return api
end
return Export
