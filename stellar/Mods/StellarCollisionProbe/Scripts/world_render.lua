-- Stellar Blade's 3D world drawing, switched off while Minecraft draws EVE from her streamed pose alone.
-- Game-thread only. Pinned SB 573aaff1: UGameViewportClient word 0x6c bit 2 is bDisableWorldRendering (read at
-- 0x42cd100 on UWorld::GetGameViewport); SB sets and clears it itself (0x3f162d6, 0x3f16628). EVE's body ticks its
-- pose only while rendered within the last second (OnlyTickPoseWhenRendered, 0x411f210), unless SB's
-- AlwaysTickPoseDistance (+0x7d0) covers the distance to its camera (0x411f080), so that guard goes on first.
local UE=require('UEHelpers')
local Render={}
local BIT,FAR=4,1e7 -- FAR: 100 km in cm, any camera distance.
local function valid(object) return object and object:IsValid() end
function Render.new(log)
    local api={}
    -- saved: the viewport and whether SB had the bit set, then {handle, previous distance} by component address.
    local registered,swept,saved,guarded,last=false,false,nil,0,'SB world drawing on'
    local function note(message) if message~=last then last=message; log('Render: '..message) end end
    local function viewport()
        if not registered then
            assert(type(RegisterCustomProperty)=='function' and PropertyTypes and PropertyTypes.UInt32Property,
                'This loader cannot expose the viewport flags.')
            RegisterCustomProperty({Name='StellarViewportFlags',Type=PropertyTypes.UInt32Property,
                BelongsToClass='/Script/Engine.GameViewportClient',OffsetInternal=0x6c})
            registered=true
        end
        local controller=UE.GetPlayerController()
        local found=valid(controller) and valid(controller.Player) and controller.Player.ViewportClient
        assert(valid(found),'No game viewport.')
        local flags=found.StellarViewportFlags
        assert(type(flags)=='number' and flags%1==0 and flags>=0 and flags<=0xffffffff,'Viewport flags are unavailable.')
        return found,flags
    end
    -- A cut a Lua reload left behind: EVE's body at FAR (SB's own distance is 0) marks it, and the bit is then taken
    -- for ours. ponytail: only her current body is checked; a pawn replaced since (a map load) loses the mark, so restart
    -- Stellar Blade then. Her other meshes keep FAR until the next cut adopts them at 0.
    local function sweep()
        local pawn=UE.GetPlayer()
        local mesh=valid(pawn) and pawn.Mesh
        if not valid(mesh) or mesh.AlwaysTickPoseDistance~=FAR then return end
        saved,guarded={far={[mesh:GetAddress()]={mesh,0}}},1
        note('restoring the cut a Lua reload left')
        local found,flags=viewport()
        saved.viewport=found
        if flags&BIT~=0 then saved.bit=false end
    end
    local function apply(components)
        assert(#components>0,'No streamed mesh to keep ticking.')
        saved=saved or {far={}}
        for _,c in ipairs(components) do
            local h=c.handle
            if not saved.far[c.object] then
                local old=h.AlwaysTickPoseDistance
                assert(type(old)=='number','AlwaysTickPoseDistance is unavailable.')
                if old==FAR then old=0 end -- A Lua reload's leftover.
                saved.far[c.object]={h,old}; guarded=guarded+1
            end
            if h.AlwaysTickPoseDistance~=FAR then h.AlwaysTickPoseDistance=FAR end
        end
        if saved.bit~=nil then return end
        local found,flags=viewport()
        saved.viewport,saved.bit=found,flags&BIT~=0
        if not saved.bit then found.StellarViewportFlags=flags|BIT end
    end
    -- The bit first, so EVE never stays unrendered without her guard; a failure keeps what is left for the next call.
    local function restore()
        if not saved then return end
        if saved.bit==false and valid(saved.viewport) then
            saved.viewport.StellarViewportFlags=saved.viewport.StellarViewportFlags&~BIT
        end
        saved.bit=nil -- SB's own bit is left as it was.
        for object,e in pairs(saved.far) do
            if valid(e[1]) then e[1].AlwaysTickPoseDistance=e[2] end
            saved.far[object]=nil; guarded=guarded-1
        end
        saved=nil
    end
    -- Returns true once SB draws its world again, else false and why.
    function api.restore()
        if not saved then return true end
        local ok,why=pcall(restore)
        if ok then note('SB world drawing on'); return true end
        note('SB world drawing restore failed: '..tostring(why))
        return false,'Stellar Blade world drawing could not be restored: '..tostring(why)
    end
    -- Each bridge tick: active while the avatar stream is bound and nothing captures; components: the streamed meshes.
    -- ponytail: the bit is written once per activation; if SB clears it meanwhile, its world draws until the next one.
    function api.tick(active,components)
        if not swept then swept=true; pcall(sweep) end
        if not active then return api.restore() end
        local ok,why=pcall(apply,components)
        if ok then note(string.format('SB world drawing off (pose ticking kept on %d meshes)',guarded)); return true end
        local undone,undo_error=pcall(restore)
        note(undone and 'SB world drawing kept on: '..tostring(why) or 'SB world drawing restore failed: '..tostring(undo_error))
        return false
    end
    function api.status()
        return saved and saved.bit~=nil and string.format('SB world drawing off (pose ticking kept on %d meshes)',guarded)
            or 'SB world drawing on'
    end
    return api
end
return Render
