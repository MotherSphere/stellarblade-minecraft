package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
package.loaded.UEHelpers={}
local engine=require('capture_adapter').new()
assert(type(engine.prepare_async)=='function','The ImageWriteQueue adapter is missing')
local hook,options,unregistered,submitted
local path='/Script/Engine.Actor:SetActorHiddenInGame'
local function valid()return true end
local actor={IsValid=valid,GetAddress=function()return 123 end}
local other={IsValid=valid,GetAddress=function()return 456 end}
local target={}
local library={IsValid=valid}
function library:ExportToDisk(...)
    assert(select('#',...)==3,'The pinned ExportToDisk requires exactly three inputs')
    local texture,filename,received=...
    assert(texture==target and filename=='/private/eve-exporttest-async.png')
    assert(received.Format==0 and received.CompressionQuality==0 and received.bAsync==true and received.bOverwriteFile==false)
    assert(received.OnComplete.Object==actor and received.OnComplete.FunctionName=='SetActorHiddenInGame')
    options=received; submitted=true
end
StaticFindObject=function(name)
    assert(name=='/Script/ImageWriteQueue.Default__ImageWriteBlueprintLibrary')
    return library
end
RegisterHook=function(...)
    assert(select('#',...)==2); local name,callback=...; assert(name==path)
    hook=callback; return 41,42
end
UnregisterHook=function(...)
    assert(select('#',...)==3); local name,pre,post=...; assert(name==path and pre==41 and post==42)
    unregistered=true
end
FName=function(value)return value end
local function fire(object,value)
    return hook({get=function()return object end},{get=function()return value end})
end
local handle={actor=actor,target=target}
local completed={}
engine.prepare_async(handle,function(success)completed[#completed+1]=success end)
fire(actor,true); assert(#completed==0,'Setup calls must not signal async completion')
engine.export_async(handle,'/private/eve-exporttest-async.png'); assert(submitted and options)
fire(other,true); assert(#completed==0,'Other actors must not signal completion')
assert(fire(actor,false)==nil and #completed==1 and completed[1]==false)
fire(actor,true); assert(#completed==1,'Only the first completion is accepted')
assert(not unregistered,'Callback must not unregister itself or free native state')
engine.unwatch(handle); assert(unregistered)
engine.prepare_async(handle,function(success)completed[#completed+1]=success end)
engine.export_async(handle,'/private/eve-exporttest-async.png')
hook({get=function()return actor end},false)
assert(#completed==2 and completed[2]==false,'A direct false bool must remain a failure callback')
engine.unwatch(handle)
print('PASS ImageWriteQueue exact arity/options, owned-actor delegate filter, failure callback and deferred hook cleanup.')
