-- Stellar Blade's world drawing against a fake engine: every write in order, SB's own bit, failures.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local writes,logs={},{}
-- A reflected object: field writes are recorded in order; fail names a field whose writes throw.
local function object(name,fields)
    fields.valid=true
    fields.IsValid=function() return fields.valid end
    return setmetatable({},{__index=fields,__newindex=function(_,key,value)
        if fields.fail==key then error(name..' write failed') end
        writes[#writes+1]=string.format('%s.%s=%g',name,key,value); fields[key]=value
    end}),fields
end
local viewport,flags=object('viewport',{StellarViewportFlags=1})
local controller={IsValid=function() return true end,Player={IsValid=function() return true end,ViewportClient=viewport}}
local registered={}
PropertyTypes={UInt32Property='uint32'}
RegisterCustomProperty=function(spec) registered[#registered+1]=spec end
local body,body_fields=object('body',{AlwaysTickPoseDistance=0,GetAddress=function() return 8 end})
local pawn={IsValid=function() return true end,Mesh=body}
package.loaded.UEHelpers={GetPlayerController=function() return controller end,GetPlayer=function() return pawn end}
local drone,drone_fields=object('drone',{AlwaysTickPoseDistance=250})
local meshes={{handle=body,object=8},{handle=drone,object=16}}
local render=require('world_render').new(function(message) logs[#logs+1]=message end)
local function reset() writes={}; logs={} end
local function trace() return table.concat(writes,' ') end

-- Apply: both guards before the bit; the next ticks write nothing unless a guard was changed behind it.
assert(render.tick(true,meshes))
assert(trace()=='body.AlwaysTickPoseDistance=1e+07 drone.AlwaysTickPoseDistance=1e+07 viewport.StellarViewportFlags=5',trace())
assert(#registered==1 and registered[1].Name=='StellarViewportFlags' and registered[1].Type=='uint32'
    and registered[1].BelongsToClass=='/Script/Engine.GameViewportClient' and registered[1].OffsetInternal==0x6c)
assert(#logs==1 and logs[1]=='Render: SB world drawing off (pose ticking kept on 2 meshes)',logs[1])
assert(render.status()=='SB world drawing off (pose ticking kept on 2 meshes)')
reset(); render.tick(true,meshes); assert(#writes==0 and #logs==0,'A held cut writes and logs nothing')
body_fields.AlwaysTickPoseDistance=0; render.tick(true,meshes)
assert(trace()=='body.AlwaysTickPoseDistance=1e+07','A guard changed behind the cut is set again, the bit left alone')
-- Restore: the bit first, then each guard back to its own value; once only.
reset(); assert(render.tick(false))
assert(writes[1]=='viewport.StellarViewportFlags=1' and #writes==3 and body_fields.AlwaysTickPoseDistance==0
    and drone_fields.AlwaysTickPoseDistance==250,trace())
assert(#logs==1 and logs[1]=='Render: SB world drawing on' and render.status()=='SB world drawing on')
reset(); assert(render.tick(false) and render.restore()); assert(#writes==0 and #logs==0,'Nothing to restore twice')
assert(#registered==1,'The flags are registered once per Lua lifetime')
-- A mesh streamed after the cut gets its guard and its own value back; a viewport gone since is left alone.
render.tick(true,{meshes[1]}); reset(); render.tick(true,meshes)
assert(trace()=='drone.AlwaysTickPoseDistance=1e+07' and logs[1]=='Render: SB world drawing off (pose ticking kept on 2 meshes)',trace())
render.tick(false); assert(body_fields.AlwaysTickPoseDistance==0 and drone_fields.AlwaysTickPoseDistance==250)
render.tick(true,meshes); flags.valid=false; reset()
assert(render.restore() and not trace():find('viewport',1,true) and body_fields.AlwaysTickPoseDistance==0
    and drone_fields.AlwaysTickPoseDistance==250,trace())
flags.valid=true

-- SB already draws nothing: its bit stays set through the cut and after it.
flags.StellarViewportFlags=4|2; reset(); render.tick(true,meshes)
assert(trace()=='body.AlwaysTickPoseDistance=1e+07 drone.AlwaysTickPoseDistance=1e+07',trace())
reset(); render.tick(false)
assert(flags.StellarViewportFlags==6 and #writes==2 and body_fields.AlwaysTickPoseDistance==0,'SB keeps its own bit')
flags.StellarViewportFlags=1

-- A capture start restores at once; a mesh the engine deleted meanwhile is skipped.
render.tick(true,meshes); drone_fields.valid=false; reset()
assert(render.restore() and trace()=='viewport.StellarViewportFlags=1 body.AlwaysTickPoseDistance=0',trace())
drone_fields.valid=true; drone_fields.AlwaysTickPoseDistance=250

-- Apply failure: no viewport means the guards are undone and the bit never written, said once.
controller.Player.ViewportClient=nil; reset()
assert(render.tick(true,meshes)==false and render.tick(true,meshes)==false)
assert(body_fields.AlwaysTickPoseDistance==0 and drone_fields.AlwaysTickPoseDistance==250 and not trace():find('viewport',1,true))
assert(#logs==1 and logs[1]:find('Render: SB world drawing kept on: ',1,true) and logs[1]:find('No game viewport',1,true),logs[1])
assert(render.status()=='SB world drawing on')
controller.Player.ViewportClient=viewport
reset(); assert(render.tick(true,{}) ==false and #writes==0,'No streamed mesh, no cut')

-- Restore failure: the guards stay while the bit cannot be cleared, and the next restore finishes the job.
render.tick(true,meshes); flags.fail='StellarViewportFlags'; reset()
local ok,why=render.restore()
assert(not ok and why:find('could not be restored',1,true) and #writes==0 and body_fields.AlwaysTickPoseDistance==1e7,
    'A bit still set keeps every guard')
assert(logs[#logs]:find('Render: SB world drawing restore failed: ',1,true))
flags.fail=nil; reset(); assert(render.tick(false))
assert(trace():find('^viewport.StellarViewportFlags=1') and body_fields.AlwaysTickPoseDistance==0 and logs[1]=='Render: SB world drawing on')
-- A guard that cannot be restored is retried alone, after the bit.
render.tick(true,meshes); body_fields.fail='AlwaysTickPoseDistance'; reset()
assert(not render.restore() and flags.StellarViewportFlags==1)
body_fields.fail=nil; reset(); assert(render.restore() and not trace():find('viewport',1,true) and body_fields.AlwaysTickPoseDistance==0)
-- A guard that cannot be written leaves SB drawing.
body_fields.fail='AlwaysTickPoseDistance'; reset()
assert(render.tick(true,meshes)==false and flags.StellarViewportFlags==1 and not trace():find('viewport',1,true))
body_fields.fail=nil

-- A Lua reload mid-cut: the next lifetime takes the bit over EVE's guarded body for its own and restores both at once,
-- then adopts a mesh still at the guard at Stellar Blade's 0.
render.tick(true,meshes); assert(flags.StellarViewportFlags==5)
package.loaded.world_render=nil
local reloaded=require('world_render').new(function(message) logs[#logs+1]=message end)
reset(); assert(reloaded.tick(false))
assert(flags.StellarViewportFlags==1 and body_fields.AlwaysTickPoseDistance==0 and drone_fields.AlwaysTickPoseDistance==1e7,trace())
assert(#logs==2 and logs[1]=='Render: restoring the cut a Lua reload left' and logs[2]=='Render: SB world drawing on',logs[1])
reloaded.tick(true,meshes); reloaded.tick(false); assert(flags.StellarViewportFlags==1 and drone_fields.AlwaysTickPoseDistance==0)
body_fields.AlwaysTickPoseDistance=1e7; reset(); reloaded.tick(false); assert(#writes==0,'Swept once per Lua lifetime')

-- The ponytail's cloth ticks only while rendered at OnlyTickPoseWhenRendered (3): AlwaysTickPose (1) during the cut,
-- before the bit, and its own value back after; any other value is left alone.
package.loaded.world_render=nil
local cloth_render=require('world_render').new(function() end)
local tail,tail_fields=object('tail',{AlwaysTickPoseDistance=0,VisibilityBasedAnimTickOption=3})
local worn={{handle=body,object=8},{handle=tail,object=24,cloth=true}}
body_fields.AlwaysTickPoseDistance=0; flags.StellarViewportFlags=1
cloth_render.tick(false); reset(); assert(cloth_render.tick(true,worn))
assert(trace()=='body.AlwaysTickPoseDistance=1e+07 tail.VisibilityBasedAnimTickOption=1 tail.AlwaysTickPoseDistance=1e+07 '
    ..'viewport.StellarViewportFlags=5',trace())
reset(); cloth_render.tick(true,worn); assert(#writes==0,'A held cut leaves the tick option alone')
reset(); assert(cloth_render.tick(false))
assert(writes[1]=='viewport.StellarViewportFlags=1' and tail_fields.VisibilityBasedAnimTickOption==3
    and trace():find('tail.VisibilityBasedAnimTickOption=3',1,true),trace())
tail_fields.VisibilityBasedAnimTickOption=0; reset(); cloth_render.tick(true,worn)
assert(not trace():find('VisibilityBasedAnimTickOption',1,true),'Another tick option is left alone')
reset(); cloth_render.tick(false); assert(not trace():find('VisibilityBasedAnimTickOption',1,true) and tail_fields.VisibilityBasedAnimTickOption==0)
tail_fields.VisibilityBasedAnimTickOption=nil; reset()
assert(cloth_render.tick(true,worn)==false and flags.StellarViewportFlags==1 and body_fields.AlwaysTickPoseDistance==0,
    'An unreadable tick option keeps SB drawing')
print('PASS world drawing: guards before the bit, bit before guards on restore, SB-owned bit kept, apply and restore failures, Lua reload, cloth tick option')
