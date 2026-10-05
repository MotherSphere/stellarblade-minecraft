package.path='stellar/Mods/StellarCollisionProbe/Scripts/?.lua;'..package.path
local loaded,Protocol=pcall(require,'scene_protocol')
assert(loaded,'scene_protocol must validate the shared native scene descriptor')

local session='0123456789abcdef0123456789abcdef'
local digest=string.rep('a',64)
local file='mc-scene-'..session..'-'..digest..'.png'
local fixture=table.concat({
    'SBMC_SCENE 1 '..session..' 7 1791110000 3',
    'DIM minecraft:overworld','ORIGIN 0.5 64 -0.5','COUNTS 1 2',
    'TEXTURE 0 '..file..' 4096 256 256',
    'FACE 0 0 0 0 U','FACE 0 0 -1 0 D','END '..session..' 7','',
},'\n')
local function copy(value)
    if type(value)~='table' then return value end
    local result={}; for k,v in pairs(value) do result[k]=copy(v) end; return result
end
local function reject(text)
    local ok,value,reason=pcall(Protocol.parse,text)
    assert(ok and value==nil and type(reason)=='string','invalid scene must return nil,error')
end
local packet=assert(Protocol.parse(fixture))
assert(packet.session==session and packet.revision==7 and packet.collision_revision==3)
assert(packet.time==1791110000 and packet.dimension=='minecraft:overworld')
assert(packet.origin.x==0.5 and packet.origin.y==64 and packet.origin.z==-0.5)
assert(#packet.textures==1 and packet.textures[1].index==0 and packet.textures[1].file==file)
assert(packet.textures[1].size==4096 and packet.textures[1].width==256 and packet.textures[1].height==256)
assert(#packet.faces==2 and packet.faces[1].texture==0 and packet.faces[1].direction=='U')
assert(packet.faces[2].center.y==-1)
assert(Protocol.parse((fixture:gsub('FACE 0 0 0 0 U','FACE 0 0e0 0.0 -0 U'))))

for _,bad in ipairs({
    fixture:sub(1,-2),fixture..'\n',fixture:gsub('\n','\r\n'),fixture:gsub('DIM ','DIM  '),
    fixture:gsub('SBMC_SCENE 1','SBMC_SCENE 2'),fixture:gsub('DIM','DIM\0'),
    fixture:gsub('minecraft:overworld','Minecraft:overworld'),fixture:gsub('minecraft:overworld','overworld'),
    fixture:gsub(session,string.rep('A',32)),fixture:gsub(session,string.rep('a',31)),
    fixture:gsub(' 7 1791110000',' 0 1791110000'),fixture:gsub(' 7 1791110000',' 2147483648 1791110000'),
    fixture:gsub(' 1791110000 3',' 1791110000 0'),fixture:gsub(' 1791110000 3',' 1791110000 2147483648'),
    fixture:gsub('1791110000','-1'),fixture:gsub('1791110000','9007199254740992'),
    fixture:gsub('ORIGIN 0%.5 64 %-0%.5','ORIGIN 30000001 64 -0.5'),
    fixture:gsub('ORIGIN 0%.5 64 %-0%.5','ORIGIN 0.5 4097 -0.5'),
    fixture:gsub('FACE 0 0 0 0 U','FACE 0 nan 0 0 U'),fixture:gsub('FACE 0 0 0 0 U','FACE 0 1e999 0 0 U'),
    fixture:gsub('FACE 0 0 0 0 U','FACE 0 0x1 0 0 U'),fixture:gsub('FACE 0 0 0 0 U','FACE 0 33 0 0 U'),
    fixture:gsub('COUNTS 1 2','COUNTS 33 2'),fixture:gsub('COUNTS 1 2','COUNTS 1 2049'),
    fixture:gsub('COUNTS 1 2','COUNTS 1 1'),fixture:gsub('TEXTURE 0','TEXTURE 1'),
    fixture:gsub('4096 256 256','0 256 256'),fixture:gsub('4096 256 256','1048577 256 256'),
    fixture:gsub('4096 256 256','4096 255 256'),fixture:gsub('4096 256 256','4096 256 257'),
    fixture:gsub('mc%-scene%-','../mc-scene-',1),fixture:gsub(digest,string.rep('A',64)),
    fixture:gsub(digest,string.rep('a',63)),fixture:gsub('mc%-scene%-'..session,'mc-scene-'..string.rep('b',32)),
    fixture:gsub('FACE 0 0 0 0 U','FACE 1 0 0 0 U'),fixture:gsub('FACE 0 0 0 0 U','FACE 0 0 0 0 Q'),
    fixture:gsub('FACE 0 0 %-1 0 D','FACE 0 -0 0.0 0e0 U'),
    fixture:gsub('END '..session..' 7','END '..session..' 8'),
    fixture:gsub('END '..session..' 7','END '..string.rep('b',32)..' 7'),
    string.rep('x',1048577),
}) do reject(bad) end
reject(nil)
local duplicate=fixture:gsub('COUNTS 1 2','COUNTS 2 2'):gsub('FACE 0 0 0 0 U',
    'TEXTURE 1 '..file..' 4096 256 256\nFACE 0 0 0 0 U')
reject(duplicate)
local empty=fixture:gsub('COUNTS 1 2.-END','COUNTS 0 0\nEND')
assert(#assert(Protocol.parse(empty)).faces==0,'an empty patch is valid')
local limit={'SBMC_SCENE 1 '..session..' 2147483647 9007199254740991 2147483647',
    'DIM minecraft:overworld','ORIGIN 0.5 64 -0.5','COUNTS 32 2048'}
for i=0,31 do
    limit[#limit+1]=string.format('TEXTURE %d mc-scene-%s-%064x.png 1048576 256 256',i,session,i)
end
for i=1,2048 do limit[#limit+1]=string.format('FACE 31 %.4f 0 0 U',i/1000) end
limit[#limit+1]='END '..session..' 2147483647\n'
assert(#assert(Protocol.parse(table.concat(limit,'\n'))).faces==2048,'inclusive descriptor limits must parse')
assert(Protocol.fresh(100,105) and Protocol.fresh(102,100))
assert(not Protocol.fresh(100,106) and not Protocol.fresh(103,100) and not Protocol.fresh(nil,100))

local binding={session=session,dimension='minecraft:overworld',origin={x=0.5,y=64,z=-0.5},
    bounds={-4.5,-2,-4.5,4.5,4,4.5},ack=3,state='entered',position={x=0,y=0,z=0}}
assert(Protocol.validate_binding(packet,binding))
for key,value in pairs({session=string.rep('b',32),dimension='minecraft:the_nether',ack=4,state='ready'}) do
    local changed=copy(binding); changed[key]=value
    local ok,reason=Protocol.validate_binding(packet,changed)
    assert(not ok and type(reason)=='string','reject binding mismatch: '..key)
end
local changed=copy(binding); changed.origin.x=1.5
assert(not Protocol.validate_binding(packet,changed))
assert(not Protocol.validate_binding(packet,nil))
local outer=copy(packet); outer.faces={{texture=0,center={x=-4.5,y=-0.5,z=0},direction='W'},
    {texture=0,center={x=0,y=4,z=0},direction='U'}}
assert(Protocol.validate_binding(outer,binding),'outward faces may lie on inclusive patch boundaries')
outer.faces[1].center.z=4.1
assert(not Protocol.validate_binding(outer,binding),'all face corners must remain within patch bounds')
outer.faces[1].center.z=0; outer.faces[1].direction='E'
assert(not Protocol.validate_binding(outer,binding),'a face cannot belong to a block outside the patch')

local heartbeat=copy(packet); heartbeat.time=packet.time+1
assert(Protocol.same_payload(packet,heartbeat),'unchanged payload can refresh time without mesh work')
for key,value in pairs({session=string.rep('b',32),revision=8,collision_revision=4,dimension='minecraft:the_nether'}) do
    local edited=copy(heartbeat); edited[key]=value
    assert(not Protocol.same_payload(packet,edited),'heartbeat must preserve '..key)
end
for _,edit in ipairs({
    function(p) p.origin.x=p.origin.x+1 end,
    function(p) p.textures[1].file='another.png' end,
    function(p) p.textures[1].size=4097 end,
    function(p) p.textures[1].width=255 end,
    function(p) p.faces[1].center.x=1 end,
    function(p) p.faces[1].direction='D' end,
    function(p) p.faces[1].texture=1 end,
    function(p) table.remove(p.faces) end,
}) do
    local edited=copy(heartbeat); edit(edited)
    assert(not Protocol.same_payload(packet,edited),'changed payload cannot masquerade as a heartbeat')
end
assert(not Protocol.same_payload(packet,nil))

local shared=assert(io.open(arg[1] or 'protocol/fixtures/native-scene.txt','rb'))
local value=shared:read('*a'); shared:close()
local java=assert(Protocol.parse(value),'Java synthetic scene must obey the same Lua contract')
assert(java.session==session and java.revision==1 and java.collision_revision==1)
assert(java.origin.x==0.5 and java.origin.y==64 and java.origin.z==0.5)
assert(#java.textures==1 and java.textures[1].size==1024 and #java.faces==6)
assert(java.faces[1].direction=='D' and java.faces[1].center.y==0)
assert(java.faces[2].direction=='U' and java.faces[2].center.y==1)
assert(java.faces[3].direction=='N' and java.faces[3].center.z==-0.5)
assert(java.faces[4].direction=='S' and java.faces[4].center.z==0.5)
assert(java.faces[5].direction=='W' and java.faces[5].center.x==-0.5)
assert(java.faces[6].direction=='E' and java.faces[6].center.x==0.5)
local shared_binding=copy(binding); shared_binding.origin.z=0.5; shared_binding.ack=1
assert(Protocol.validate_binding(java,shared_binding))
print('PASS scene protocol: limits, strict framing, complete binding, bounds and immutable heartbeat payloads')
