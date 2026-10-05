package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Geometry=pcall(require,'scene_geometry')
assert(loaded,'scene_geometry must map Minecraft faces into native mesh sections')

local function vector(value,x,y,z)
    assert(value.X==x and value.Y==y and value.Z==z,'unexpected native vector')
end
-- Expected first/second vertex and normal derive from outside-facing Minecraft UVs.
local cases={
    D={{5,35,20},{15,35,20},{0,0,-1}},
    U={{5,25,20},{15,25,20},{0,0,1}},
    N={{15,30,25},{5,30,25},{0,-1,0}},
    S={{5,30,25},{15,30,25},{0,1,0}},
    W={{10,25,25},{10,35,25},{-1,0,0}},
    E={{10,35,25},{10,25,25},{1,0,0}},
}
for direction,expected in pairs(cases) do
    local sections=Geometry.groups({faces={{texture=2,center={x=1,y=2,z=3},direction=direction}}},10)
    assert(#sections==1 and sections[1].texture==2)
    local section=sections[1]
    assert(#section.vertices==4 and #section.triangles==6 and #section.normals==4)
    assert(#section.uv0==4 and #section.tangents==4 and #section.colors==4)
    vector(section.vertices[1],table.unpack(expected[1])); vector(section.vertices[2],table.unpack(expected[2]))
    vector(section.normals[1],table.unpack(expected[3]))
    local sum={X=0,Y=0,Z=0}
    for i=1,4 do
        local vertex=section.vertices[i]
        sum.X=sum.X+vertex.X; sum.Y=sum.Y+vertex.Y; sum.Z=sum.Z+vertex.Z
        vector(section.normals[i],table.unpack(expected[3]))
        assert(section.colors[i].R==255 and section.colors[i].G==255 and section.colors[i].B==255 and section.colors[i].A==255)
        assert(section.tangents[i].bFlipTangentY==false)
    end
    vector(sum,40,120,80)
    assert(section.uv0[1].X==0 and section.uv0[1].Y==0 and section.uv0[2].X==1 and section.uv0[2].Y==0)
    assert(section.uv0[3].X==1 and section.uv0[3].Y==1 and section.uv0[4].X==0 and section.uv0[4].Y==1)
    for i=1,6,3 do
        local a,b,c=section.vertices[section.triangles[i]+1],section.vertices[section.triangles[i+1]+1],section.vertices[section.triangles[i+2]+1]
        local ab,ac={b.X-a.X,b.Y-a.Y,b.Z-a.Z},{c.X-a.X,c.Y-a.Y,c.Z-a.Z}
        local cross={ab[2]*ac[3]-ab[3]*ac[2],ab[3]*ac[1]-ab[1]*ac[3],ab[1]*ac[2]-ab[2]*ac[1]}
        assert(cross[1]*expected[3][1]+cross[2]*expected[3][2]+cross[3]*expected[3][3]==-100,
            'target Unreal front-face winding must have negative cross-product dot outward normal')
    end
    local a,b=section.vertices[1],section.vertices[2]
    vector(section.tangents[1].TangentX,(b.X-a.X)/10,(b.Y-a.Y)/10,(b.Z-a.Z)/10)
end
local grouped=Geometry.groups({faces={
    {texture=4,center={x=0,y=0,z=0},direction='U'},
    {texture=1,center={x=1,y=0,z=0},direction='E'},
    {texture=4,center={x=2,y=0,z=0},direction='U'},
}},100)
assert(#grouped==2 and grouped[1].texture==1 and grouped[2].texture==4,'sections sort by explicit material index')
assert(#grouped[2].vertices==8 and #grouped[2].triangles==12)
assert(grouped[2].triangles[7]==4 and grouped[2].triangles[8]==6 and grouped[2].triangles[9]==5,
    'subsequent faces must offset their zero-based native triangle indices')
assert(#Geometry.groups({faces={}},100)==0)
for _,scale in ipairs({0,-1,math.huge,0/0}) do assert(not pcall(Geometry.groups,{faces={}},scale)) end
local shared=assert(io.open('protocol/fixtures/native-scene.txt','rb'))
local text=shared:read('*a'); shared:close()
local cube=Geometry.groups(assert(require('scene_protocol').parse(text)),100)
assert(#cube==1 and #cube[1].vertices==24 and #cube[1].triangles==36)
for _,vertex in ipairs(cube[1].vertices) do
    assert((vertex.X==-50 or vertex.X==50) and (vertex.Y==-50 or vertex.Y==50)
        and (vertex.Z==0 or vertex.Z==100),'shared Java cube must map to native 100-unit edges')
end
print('PASS scene geometry: positive basis, all six UV orientations, target front-face winding and grouped native arrays')
