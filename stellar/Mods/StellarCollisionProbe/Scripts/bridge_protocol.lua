local Protocol = {}
local MAX_SEQUENCE = 2147483647
local MAX_TIME = 9007199254740991
local MAX_BOXES = 1024

local function need(condition, message)
    if not condition then error(message, 0) end
end

local function finite(value, limit)
    return type(value) == 'number' and value == value and math.abs(value) <= limit
end

local function integer(value, minimum, maximum)
    return finite(value, maximum) and value >= minimum and value % 1 == 0
end

local function decimal(token, limit)
    local mantissa, exponent = token:match('^(.-)[eE](.*)$')
    if mantissa then
        need(exponent:match('^[+-]?%d+$'), 'invalid exponent')
    else
        mantissa = token
    end
    mantissa = mantissa:gsub('^[+-]', '')
    need(mantissa:match('^%d+%.?%d*$') or mantissa:match('^%.%d+$'), 'invalid decimal')
    local value = tonumber(token)
    need(finite(value, limit), 'number outside limits')
    return value
end

local function unsigned(token, minimum, maximum)
    need(token:match('^%d+$'), 'invalid integer')
    local value = tonumber(token)
    need(integer(value, minimum, maximum), 'integer outside limits')
    return value
end

local function session(value)
    return type(value) == 'string' and #value == 32 and value:match('^[0-9a-f]+$')
end

local function fields(line, name, count)
    need(type(line) == 'string', 'missing line')
    local result = {}
    for field in line:gmatch('%S+') do result[#result + 1] = field end
    need(#result == count and result[1] == name and table.concat(result, ' ') == line,
        'invalid ' .. name .. ' line')
    return result
end

local function box(line, name)
    local values = fields(line, name, 7)
    local result = {}
    for i = 1, 6 do result[i] = decimal(values[i + 1], 32) end
    for axis = 1, 3 do need(result[axis] < result[axis + 3], 'nonpositive box extent') end
    return result
end

local function packet_lines(text)
    need(type(text) == 'string' and #text <= 65536 and text:sub(-1) == '\n', 'invalid packet size or ending')
    need(not text:find('[^\n\32-\126]'), 'packet must contain printable ASCII and LF')
    local lines = {}
    for line in text:gmatch('([^\n]*)\n') do lines[#lines + 1] = line end
    return lines
end

-- Exact identity of a box's six coordinates.
function Protocol.box_key(b)
    return string.format('%.17g %.17g %.17g %.17g %.17g %.17g', b[1], b[2], b[3], b[4], b[5], b[6])
end

function Protocol.parse_terrain(text)
    local ok, result = pcall(function()
        local lines = packet_lines(text)
        need(lines[1] ~= 'SBMC 1 TERRAIN', 'terrain format 1 is from an older Minecraft mod; install the matching JAR')
        need(#lines >= 8 and #lines <= MAX_BOXES + 8 and lines[1] == 'SBMC 2 TERRAIN', 'invalid terrain framing')
        local identity = fields(lines[2], 'SESSION', 2)[2]
        need(session(identity), 'invalid session')
        local seq = unsigned(fields(lines[3], 'SEQ', 2)[2], 1, MAX_SEQUENCE)
        local time = unsigned(fields(lines[4], 'TIME', 2)[2], 0, MAX_TIME)
        local world = fields(lines[5], 'WORLD', 5)
        need(world[2]:match('^[a-z0-9_.-]+:[a-z0-9_./-]+$'), 'invalid dimension')
        local origin = {x=decimal(world[3], 30000000), y=decimal(world[4], 4096), z=decimal(world[5], 30000000)}
        local bounds = box(lines[6], 'BOUNDS')
        local count = unsigned(fields(lines[7], 'COUNT', 2)[2], 0, MAX_BOXES)
        need(#lines == count + 8, 'box count mismatch')
        local boxes, seen = {}, {}
        for i = 1, count do
            boxes[i] = box(lines[i + 7], 'BOX')
            for axis = 1, 3 do
                need(boxes[i][axis] >= bounds[axis] and boxes[i][axis + 3] <= bounds[axis + 3],
                    'box outside region')
            end
            local key = Protocol.box_key(boxes[i])
            need(not seen[key], 'duplicate box')
            seen[key] = true
        end
        need(unsigned(fields(lines[#lines], 'END', 2)[2], 1, MAX_SEQUENCE) == seq, 'footer sequence mismatch')
        return {session=identity, seq=seq, time=time, dimension=world[2], origin=origin, bounds=bounds, boxes=boxes}
    end)
    if ok then return result end
    return nil, tostring(result)
end

-- Heartbeats rewrite only TIME: a packet whose other bytes match the cached one reuses its parse.
function Protocol.parse_terrain_cached(text, cache)
    local head, time, tail
    if type(text) == 'string' then head, time, tail = text:match('^([^\n]*\n[^\n]*\n[^\n]*\n)TIME (%d+)(\n.*)$') end
    if head and #text <= 65536 and head == cache.head and tail == cache.tail then
        local value = tonumber(time)
        if integer(value, 0, MAX_TIME) then
            local p = cache.packet
            return {session=p.session, seq=p.seq, time=value, dimension=p.dimension, origin=p.origin,
                bounds=p.bounds, boxes=p.boxes}
        end
    end
    local packet, err = Protocol.parse_terrain(text)
    if packet then cache.head, cache.tail, cache.packet = head, tail, packet end
    return packet, err
end

function Protocol.parse_eve(text)
    local ok, result = pcall(function()
        local lines = packet_lines(text)
        need(#lines == 7 and lines[1] == 'SBMC 1 EVE', 'invalid EVE framing')
        local identity = fields(lines[2], 'SESSION', 2)[2]
        need(session(identity), 'invalid session')
        local seq = unsigned(fields(lines[3], 'SEQ', 2)[2], 1, MAX_SEQUENCE)
        local time = unsigned(fields(lines[4], 'TIME', 2)[2], 0, MAX_TIME)
        local state = fields(lines[5], 'STATE', 3)
        need(state[2] == 'ready' or state[2] == 'entered' or state[2] == 'idle', 'invalid state')
        local ack = unsigned(state[3], 1, MAX_SEQUENCE)
        local values = fields(lines[6], 'POS', 4)
        local position = {x=decimal(values[2], 64), y=decimal(values[3], 64), z=decimal(values[4], 64)}
        need(unsigned(fields(lines[7], 'END', 2)[2], 1, MAX_SEQUENCE) == seq, 'footer sequence mismatch')
        return {session=identity, seq=seq, time=time, state=state[2], ack=ack, position=position}
    end)
    if ok then return result end
    return nil, tostring(result)
end

function Protocol.encode_eve(eve)
    need(type(eve) == 'table' and session(eve.session), 'invalid session')
    need(integer(eve.seq, 1, MAX_SEQUENCE) and integer(eve.ack, 1, MAX_SEQUENCE), 'invalid sequence')
    need(integer(eve.time, 0, MAX_TIME), 'invalid timestamp')
    need(eve.state == 'ready' or eve.state == 'entered' or eve.state == 'idle', 'invalid state')
    need(type(eve.position) == 'table', 'invalid position')
    for _, axis in ipairs({'x', 'y', 'z'}) do need(finite(eve.position[axis], 64), 'position outside limits') end
    local function number(value) return (string.format('%.17g', value):gsub(',', '.')) end
    return string.format('SBMC 1 EVE\nSESSION %s\nSEQ %d\nTIME %d\nSTATE %s %d\nPOS %s %s %s\nEND %d\n',
        eve.session, eve.seq, eve.time, eve.state, eve.ack,
        number(eve.position.x), number(eve.position.y), number(eve.position.z), eve.seq)
end

function Protocol.fresh(time, now)
    return integer(time, 0, MAX_TIME) and integer(now, 0, MAX_TIME) and now - time >= -2 and now - time <= 5
end

return Protocol
