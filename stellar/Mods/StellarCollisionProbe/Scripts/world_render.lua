-- Stellar Blade's 3D world drawing, switched off while Minecraft draws EVE from her streamed pose alone.
-- Game-thread only. Pinned SB 573aaff1: UGameViewportClient word 0x6c bit 2 is bDisableWorldRendering (read at
-- 0x42cd100 on UWorld::GetGameViewport); SB sets and clears it itself (0x3f162d6, 0x3f16628). EVE's body ticks its
-- pose only while rendered within the last second (OnlyTickPoseWhenRendered, 0x411f210), unless SB's
-- AlwaysTickPoseDistance (+0x7d0) covers the distance to its camera (0x411f080), so that guard goes on first. The
-- ponytail's cloth tick (0x4534ea0) ignores that distance: with VisibilityBasedAnimTickOption OnlyTickPoseWhenRendered
-- (3) it stops unrendered, so the cloth component (marked cloth by pose_stream.lua) gets AlwaysTickPose (1) meanwhile.
local UE=require('UEHelpers')
local Render={}
local BIT,FAR=4,1e7 -- FAR: 100 km in cm, any camera distance.
local RENDERED,ALWAYS=3,1 -- EVisibilityBasedAnimTickOption OnlyTickPoseWhenRendered, AlwaysTickPose.
local LINES=150 -- Cloth state lines per Lua load.
local function valid(object) return object and object:IsValid() end
local function shown(value) return value==nil and '?' or type(value)=='number' and string.format('%g',value) or tostring(value) end
-- cloth_state(component), optional: the cloth component's state as pose_adapter.lua's engine.cloth reads it.
function Render.new(log,clock,cloth_state)
    clock=clock or os.clock
    local api={}
    -- saved: the viewport and whether SB had the bit set, then {handle, previous distance} by component address,
    -- {handle, previous tick option} by address for cloth components, and the cut's cloth counters.
    local registered,swept,saved,guarded,last,lines=false,false,nil,0,'SB world drawing on',0
    local function note(message) if message~=last then last=message; log('Render: '..message) end end
    local function counts(n)
        return string.format('%d ticks sampled, tick option 3 or above in %d, recently rendered in %d, suspended in %d, '
            ..'simulation disabled in %d, tick paused in %d',n.ticks,n.gated,n.rendered,n.suspended,n.disabled,n.paused)
    end
    -- Diagnostics while the cut holds, once per bridge tick: the cloth's tick gates (its cloth ticks only at a tick option
    -- below 3 or while recently rendered, unless suspended, disabled or paused), counted per cut, each change logged. The
    -- tick option is not set again here: a 3 after the guard set 1 means something else wrote it.
    local function sample(components)
        for _,c in ipairs(components) do
            local guard,ok,s=c.cloth and saved.tick[c.object]
            if guard then ok,s=pcall(cloth_state,c) end
            if ok then
                local n=saved.cloth or {ticks=0,gated=0,rendered=0,suspended=0,disabled=0,paused=0}
                saved.cloth=n
                n.ticks=n.ticks+1
                if type(s.tick)=='number' and s.tick>=RENDERED then n.gated=n.gated+1 end
                if s.rendered==true then n.rendered=n.rendered+1 end
                if s.suspended==true then n.suspended=n.suspended+1 end
                if s.disabled==true then n.disabled=n.disabled+1 end
                if s.paused==true then n.paused=n.paused+1 end
                local text=string.format('tick option %s (guard %s), recently rendered %s, suspended %s, disabled %s, tick paused %s',
                    shown(s.tick),guard[2]==RENDERED and 'set '..ALWAYS or 'left '..shown(guard[2]),shown(s.rendered),
                    shown(s.suspended),shown(s.disabled),shown(s.paused))
                if text~=n.seen and lines<=LINES then
                    lines=lines+1
                    log(lines<=LINES and string.format('Cloth state at %s (t %.2f): %s',os.date('%H:%M:%S'),clock(),text)
                        or 'Cloth state changes not logged further.')
                end
                n.seen=text
            end
        end
    end
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
        saved,guarded={far={[mesh:GetAddress()]={mesh,0}},tick={}},1
        note('restoring the cut a Lua reload left')
        local found,flags=viewport()
        saved.viewport=found
        if flags&BIT~=0 then saved.bit=false end
    end
    local function apply(components)
        assert(#components>0,'No streamed mesh to keep ticking.')
        saved=saved or {far={},tick={}}
        for _,c in ipairs(components) do
            local h=c.handle
            -- ponytail: a Lua reload mid-cut leaves the cloth at AlwaysTickPose, which only keeps ticking it unseen.
            if c.cloth and not saved.tick[c.object] then
                local old=h.VisibilityBasedAnimTickOption
                assert(type(old)=='number','VisibilityBasedAnimTickOption is unavailable.')
                saved.tick[c.object]={h,old}
                if old==RENDERED then h.VisibilityBasedAnimTickOption=ALWAYS end
            end
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
        for object,e in pairs(saved.tick) do
            if valid(e[1]) and e[1].VisibilityBasedAnimTickOption~=e[2] then e[1].VisibilityBasedAnimTickOption=e[2] end
            saved.tick[object]=nil
        end
        if saved.cloth then log('Render: cloth over the cut: '..counts(saved.cloth)) end
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
    -- Each bridge tick (main.lua: every 200 ms after the previous tick ended): active while the avatar stream is bound and
    -- nothing captures; components: the streamed meshes.
    -- ponytail: the bit is written once per activation; if SB clears it meanwhile, its world draws until the next one.
    function api.tick(active,components)
        if not swept then swept=true; pcall(sweep) end
        if not active then return api.restore() end
        local ok,why=pcall(apply,components)
        if ok then
            note(string.format('SB world drawing off (pose ticking kept on %d meshes)',guarded))
            if cloth_state then sample(components) end
            return true
        end
        local undone,undo_error=pcall(restore)
        note(undone and 'SB world drawing kept on: '..tostring(why) or 'SB world drawing restore failed: '..tostring(undo_error))
        return false
    end
    function api.status()
        return saved and saved.bit~=nil and string.format('SB world drawing off (pose ticking kept on %d meshes)',guarded)
            ..(saved.cloth and '; cloth: '..counts(saved.cloth) or '') or 'SB world drawing on'
    end
    return api
end
return Render
