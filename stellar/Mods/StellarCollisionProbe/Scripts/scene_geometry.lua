local Geometry={}
-- Native basis is Minecraft (x,z,y). Each row is outward normal, increasing U, increasing V.
-- PNG rows run downward: side tops have V=0; top north and bottom south have V=0.
local bases={
    D={{0,0,-1},{1,0,0},{0,-1,0}},
    U={{0,0,1},{1,0,0},{0,1,0}},
    N={{0,-1,0},{-1,0,0},{0,0,-1}},
    S={{0,1,0},{1,0,0},{0,0,-1}},
    W={{-1,0,0},{0,1,0},{0,0,-1}},
    E={{1,0,0},{0,-1,0},{0,0,-1}},
}
local corners={{0,0},{1,0},{1,1},{0,1}}
local function vector(value) return {X=value[1],Y=value[2],Z=value[3]} end

function Geometry.groups(packet,scale)
    assert(type(scale)=='number' and scale>0 and scale<math.huge,'invalid native scene scale')
    local by_texture,sections={},{}
    for _,face in ipairs(packet.faces) do
        local basis=assert(bases[face.direction],'invalid face direction')
        local section=by_texture[face.texture]
        if not section then
            section={texture=face.texture,vertices={},triangles={},normals={},uv0={},tangents={},colors={}}
            by_texture[face.texture]=section; sections[#sections+1]=section
        end
        local base=#section.vertices
        local center={face.center.x,face.center.z,face.center.y}
        for _,uv in ipairs(corners) do
            local vertex={}
            for axis=1,3 do
                vertex[axis]=(center[axis]+(uv[1]-0.5)*basis[2][axis]+(uv[2]-0.5)*basis[3][axis])*scale
            end
            section.vertices[#section.vertices+1]=vector(vertex)
            section.normals[#section.normals+1]=vector(basis[1])
            section.uv0[#section.uv0+1]={X=uv[1],Y=uv[2]}
            section.tangents[#section.tangents+1]={TangentX=vector(basis[2]),bFlipTangentY=false}
            section.colors[#section.colors+1]={R=255,G=255,B=255,A=255}
        end
        -- Target Unreal front faces use the opposite winding to the outward normal's cross product.
        for _,index in ipairs({0,2,1,0,3,2}) do section.triangles[#section.triangles+1]=base+index end
    end
    table.sort(sections,function(a,b) return a.texture<b.texture end)
    return sections
end

return Geometry
