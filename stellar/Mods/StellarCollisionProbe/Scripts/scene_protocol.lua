-- Bounded, data-only descriptor for the native Minecraft scene.
local Protocol={fresh=require('bridge_protocol').fresh}
local MAX_SEQUENCE,MAX_TIME,MAX_BYTES=2147483647,9007199254740991,1048576
local directions={D={2,-1},U={2,1},N={3,-1},S={3,1},W={1,-1},E={1,1}}
local axes={'x','y','z'}

local function need(condition,message)
    if not condition then error(message,0) end
end
local function finite(value,limit)
    return type(value)=='number' and value==value and math.abs(value)<=limit
end
local function unsigned(token,minimum,maximum)
    need(token:match('^%d+$'),'invalid integer')
    local value=tonumber(token)
    need(finite(value,maximum) and value>=minimum and value%1==0,'integer outside limits')
    return value
end
local function decimal(token,limit)
    local mantissa,exponent=token:match('^(.-)[eE](.*)$')
    if mantissa then need(exponent:match('^[+-]?%d+$'),'invalid exponent') else mantissa=token end
    mantissa=mantissa:gsub('^[+-]','')
    need(mantissa:match('^%d+%.?%d*$') or mantissa:match('^%.%d+$'),'invalid decimal')
    local value=tonumber(token)
    need(finite(value,limit),'number outside limits')
    return value
end
local function fields(line,name,count)
    need(type(line)=='string','missing line')
    local result={}
    for field in line:gmatch('%S+') do result[#result+1]=field end
    need(#result==count and result[1]==name and table.concat(result,' ')==line,'invalid '..name..' line')
    return result
end
local function session(value)
    return #value==32 and value:match('^[0-9a-f]+$')
end
local function position(values,offset,limits)
    return {x=decimal(values[offset],limits[1]),y=decimal(values[offset+1],limits[2]),z=decimal(values[offset+2],limits[3])}
end

function Protocol.parse(text)
    local ok,result=pcall(function()
        need(type(text)=='string' and #text<=MAX_BYTES and text:sub(-1)=='\n','invalid packet size or ending')
        need(not text:find('[^\n\32-\126]'),'packet must contain printable ASCII and LF')
        local lines={}
        for line in text:gmatch('([^\n]*)\n') do lines[#lines+1]=line end
        need(#lines>=5 and #lines<=2085,'invalid scene framing')
        local header=fields(lines[1],'SBMC_SCENE',6)
        need(header[2]=='1' and session(header[3]),'invalid scene version or session')
        local packet={session=header[3],revision=unsigned(header[4],1,MAX_SEQUENCE),
            time=unsigned(header[5],0,MAX_TIME),collision_revision=unsigned(header[6],1,MAX_SEQUENCE)}
        packet.dimension=fields(lines[2],'DIM',2)[2]
        need(packet.dimension:match('^[a-z0-9_.-]+:[a-z0-9_./-]+$'),'invalid dimension')
        packet.origin=position(fields(lines[3],'ORIGIN',4),2,{30000000,4096,30000000})
        local counts=fields(lines[4],'COUNTS',3)
        local texture_count,face_count=unsigned(counts[2],0,32),unsigned(counts[3],0,2048)
        need(#lines==texture_count+face_count+5,'scene count mismatch')
        packet.textures,packet.faces={},{}
        local files,seen_faces={},{}
        for i=1,texture_count do
            local values=fields(lines[4+i],'TEXTURE',6)
            need(unsigned(values[2],0,31)==i-1,'texture indices must be consecutive')
            local digest=values[3]:match('^mc%-scene%-'..packet.session..'%-([0-9a-f]+)%.png$')
            need(digest and #digest==64 and not files[values[3]],'invalid or duplicate texture basename')
            files[values[3]]=true
            packet.textures[i]={index=i-1,file=values[3],size=unsigned(values[4],1,MAX_BYTES),
                width=unsigned(values[5],256,256),height=unsigned(values[6],256,256)}
        end
        for i=1,face_count do
            local values=fields(lines[4+texture_count+i],'FACE',6)
            local face={texture=unsigned(values[2],0,texture_count-1),
                center=position(values,3,{32,32,32}),direction=values[6]}
            need(directions[face.direction],'invalid face direction')
            -- Normalize signed zero and decimal spellings before duplicate detection.
            local key=face.direction
            for _,axis in ipairs(axes) do
                local value=face.center[axis]
                key=key..' '..string.format('%.17g',value==0 and 0 or value)
            end
            need(not seen_faces[key],'duplicate face center and direction')
            seen_faces[key]=true; packet.faces[i]=face
        end
        local footer=fields(lines[#lines],'END',3)
        need(footer[2]==packet.session and unsigned(footer[3],1,MAX_SEQUENCE)==packet.revision,'scene footer mismatch')
        return packet
    end)
    if ok then return result end
    return nil,tostring(result)
end

function Protocol.validate_binding(packet,binding)
    local ok,reason=pcall(function()
        need(type(binding)=='table' and binding.state=='entered','native scene requires an entered bridge')
        need(packet.session==binding.session and packet.dimension==binding.dimension,'scene session or dimension mismatch')
        need(packet.collision_revision==binding.ack,'scene collision acknowledgement mismatch')
        need(type(binding.origin)=='table' and type(binding.bounds)=='table','missing bridge origin or bounds')
        for i,axis in ipairs(axes) do
            need(packet.origin[axis]==binding.origin[axis],'scene origin mismatch')
            need(finite(binding.bounds[i],32) and finite(binding.bounds[i+3],32)
                and binding.bounds[i]<binding.bounds[i+3],'invalid bridge bounds')
        end
        for _,face in ipairs(packet.faces) do
            local direction=directions[face.direction]
            need(direction,'invalid face direction')
            for i,axis in ipairs(axes) do
                local block_center=face.center[axis]-(direction[1]==i and direction[2]*0.5 or 0)
                need(block_center-0.5>=binding.bounds[i] and block_center+0.5<=binding.bounds[i+3],
                    'scene face or its block is outside patch bounds')
            end
        end
    end)
    if ok then return true end
    return false,tostring(reason)
end

local function equal(a,b,ignore_time)
    if type(a)~='table' or type(b)~='table' then return a==b end
    for key,value in pairs(a) do
        if not (ignore_time and key=='time') and not equal(value,b[key],false) then return false end
    end
    for key in pairs(b) do
        if not (ignore_time and key=='time') and a[key]==nil then return false end
    end
    return true
end
function Protocol.same_payload(a,b)
    return type(a)=='table' and type(b)=='table' and equal(a,b,true)
end

return Protocol
