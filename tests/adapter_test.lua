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
RegisterKeyBind=function(key,callback)
    assert(type(callback)=='function', 'Probe keys must not include gameplay modifiers such as Ctrl')
end
RegisterConsoleCommandHandler=function()end
Key={F2=8,F4=7,F5=0,F6=1,F7=2,F8=3,F9=4,F10=5,F11=6}; ModifierKey={CONTROL=1}
dofile('stellar/Mods/StellarCollisionProbe/Scripts/main.lua')
local spec={role='floor',center={X=0,Y=0,Z=600},extent={X=800,Y=800,Z=25}}
local handle=captured.spawn(spec)
captured.configure(handle,spec)
assert(actor.collision_enabled and box.mode==3 and box.response==2 and box.object_type==1)
assert(box.extent.Z==25)
print('PASS target-game collision API contract; native physics remains untested.')
