-- Target-game API contract: Stellar Blade extends Actor.SetActorEnableCollision.
-- This fixture validates our call shape, not Unreal's collision simulation.
package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local captured
local null = {IsValid=function() return false end}
local box = {IsValid=function() return true end, IsA=function() return true end}
function box:SetMobility(value) assert(value==2) end
function box:SetGenerateOverlapEvents(value) assert(value==false) end
function box:SetBoxExtent(value,update) self.extent=value; assert(update==true) end
function box:SetCollisionObjectType(value) self.object_type=value end
function box:SetCollisionResponseToAllChannels(value) self.response=value end
function box:SetCollisionEnabled(value) self.mode=value end
function box:GetCollisionEnabled() return self.mode end
local actor = {CollisionComponent=box,IsValid=function()return true end}
local world = {IsValid=function()return true end}
function actor:GetWorld() return world end
function actor:SetActorEnableCollision(...)
    assert(select('#',...)==2,'Stellar Blade SetActorEnableCollision requires two arguments')
    local enabled,fast=...
    assert(type(enabled)=='boolean' and type(fast)=='boolean')
    self.collision_enabled=enabled
end
function world:SpawnActor(_,position,rotation)
    assert(type(position)=='table' and type(rotation)=='table')
    return actor
end
local pawn={IsValid=function()return true end,GetWorld=function()return world end}
package.loaded.UEHelpers={GetPlayer=function()return pawn end}
package.loaded.probe={new=function(engine) captured=engine; return {} end}
StaticFindObject=function(path) return path=='/Script/Engine.TriggerBox' and world or null end
LoadAsset=function() return null end
RegisterKeyBind=function(key,modifiers,callback)
    if callback then
        assert((key==Key.F4 or key==Key.F7 or key==Key.F8) and #modifiers==1 and modifiers[1]==ModifierKey.SHIFT,'Only the explicit HD/texture/native-scene shortcuts have a modifier')
    else assert(type(modifiers)=='function','Bare probe keys must retain their callbacks') end
end
RegisterConsoleCommandHandler=function()end
Key={F1=9,F2=8,F4=7,F5=0,F6=1,F7=2,F8=3,F9=4,F10=5,F11=6}; ModifierKey={CONTROL=1,SHIFT=2}
dofile('stellar/Mods/StellarCollisionProbe/Scripts/main.lua')
local spec={role='floor',center={X=0,Y=0,Z=600},extent={X=800,Y=800,Z=25}}
local handle=captured.spawn(spec)
captured.configure(handle,spec)
assert(actor.collision_enabled and box.mode==3 and box.response==2 and box.object_type==1)
assert(box.extent.Z==25)
-- Mob walls: only EVE's capsule channel is blocked, without overlap events; collision leaves before a move into EVE and
-- comes back only after a move away from her.
local channels,order={},{}
function box:SetCollisionResponseToChannel(channel,response) channels[channel]=response end
local enable=box.SetCollisionEnabled
function box:SetCollisionEnabled(value) order[#order+1]=value; enable(self,value) end
function actor:K2_SetActorLocation(at,sweep,_,teleport) order[#order+1]='move'; assert(sweep==false and teleport==true); self.at=at; return true end
pawn.CapsuleComponent={IsValid=function() return true end,GetCollisionObjectType=function() return 24 end}
function pawn:K2_GetActorLocation() return {X=1,Y=2,Z=3} end
local wall=captured.blocker_spawn()
local info=captured.blocker_configure(wall)
assert(wall==actor and info=='blocks channel 24' and box.response==0 and channels[24]==2 and box.object_type==1 and box.mode==0 and box.CanCharacterStepUpOn==0)
order={}
captured.blocker_place(wall,{center={X=5,Y=6,Z=7},extent={X=30,Y=30,Z=97.5}},true)
captured.blocker_place(wall,{center={X=1,Y=2,Z=3},extent={X=30,Y=30,Z=97.5}},false)
assert(table.concat(order,',')=='move,1,0,move' and box.mode==0 and actor.at.X==1 and box.extent.Z==97.5,table.concat(order,','))
print('PASS target-game collision API contract; native physics remains untested.')
