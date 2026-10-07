package.path = 'stellar/Mods/StellarCollisionProbe/Scripts/?.lua;' .. package.path
local loaded, Protocol = pcall(require, 'bridge_protocol')
assert(loaded, 'bridge_protocol module must implement the local bridge wire format')

local function read(path)
    local file = assert(io.open(path, 'rb'))
    local value = file:read('*a'); file:close(); return value
end
local fixture = read('protocol/fixtures/terrain.txt')
local function reject(text)
    local ok, value, reason = pcall(Protocol.parse_terrain, text)
    assert(ok and value == nil and type(reason) == 'string', 'malformed terrain must return nil,error')
end

local terrain = assert(Protocol.parse_terrain(fixture))
assert(terrain.seq == 7 and terrain.session == '0123456789abcdef0123456789abcdef')
assert(terrain.dimension == 'minecraft:overworld' and terrain.origin.y == 64)
assert(terrain.origin.z == -0.5 and terrain.bounds[1] == -4.5)
-- Boxes are written relative to the window's minimum corner and come back relative to the origin.
assert(#terrain.boxes == 2 and table.concat(terrain.boxes[1], ' ') == '-4.5 -1.0 -4.5 4.5 0.0 4.5'
    and table.concat(terrain.boxes[2], ' ') == '1.0 0.0 -1.0 2.0 1.5 0.0', 'relative boxes are rebuilt')
assert(Protocol.parse_terrain((fixture:gsub('6%.5', '6.5e0'))))
for _, bad in ipairs({
    fixture:sub(1, -2), fixture .. '\n', fixture:gsub('\n', '\r\n'),
    fixture:gsub('END 7', 'END 8'), fixture:gsub('SEQ 7', 'SEQ 0'),
    fixture:gsub('SEQ 7', 'SEQ 2147483648'), fixture:gsub('COUNT 2', 'COUNT 257'),
    fixture:gsub('COUNT 2', 'COUNT 1'), fixture:gsub('6%.5', 'nan'),
    fixture:gsub('6%.5', '1e999'), fixture:gsub('BOX 5%.5 2', 'BOX 6.5 2'),
    fixture:gsub('BOX 5%.5 2', 'BOX -0.5 2'), fixture:gsub('3%.5 4%.5\n', '3.5 9.5\n'),
    fixture:gsub('BOX 5%.5 2 3%.5', 'BOX 5.5 2 4.49999'), fixture:gsub('64 %-0.5', '4097 -0.5'),
    fixture:gsub('BOUNDS %-4%.5', 'BOUNDS -2048.5'),
    fixture:gsub('minecraft:overworld', 'Minecraft:overworld'),
    fixture:gsub('SESSION 0', 'SESSION G'), fixture:gsub('SEQ ', 'SEQ  '),
    fixture:gsub('6%.5', '0x1'), fixture:gsub('TIME 1791110000', 'TIME -1'),
    fixture:gsub('TIME 1791110000', 'TIME 9007199254740992'),
    string.rep('x', 65537),
}) do reject(bad) end
reject(nil)
reject(fixture:gsub('BOX 5%.5 2 3%.5 6%.5 3%.5 4%.5', 'BOX 0 1 0 9 2 9'))
for _, older in ipairs({'1', '2'}) do
    local old_format, why = Protocol.parse_terrain((fixture:gsub('SBMC 3', 'SBMC ' .. older)))
    assert(old_format == nil and why:find('terrain format ' .. older .. ' is from an older Minecraft mod', 1, true),
        'an older format names the mismatched JAR')
end
-- count sixteenth-block boxes in the window whose minimum corner is (x0, y0, z0), written as Minecraft writes them.
local function boxes(count, x0, y0, z0)
    local lines = {}
    for i = 0, count - 1 do
        local x, y, z = 0.0625 + i % 32 * 1.25, 0.0625 + (i // 1024) * 2, 0.0625 + (i // 32) % 32 * 1.25
        lines[#lines + 1] = string.format('BOX %.4f %.4f %.4f %.4f %.4f %.4f\n', x, y, z, x + 0.875, y + 1.875, z + 0.875)
    end
    return (fixture:gsub('BOUNDS [^\n]*\nCOUNT 2\n.*END 7', string.format('BOUNDS %.1f %.1f %.1f %.1f %.1f %.1f\nCOUNT ',
        x0, y0, z0, x0 + 40, y0 + 24, z0 + 40) .. count .. '\n' .. table.concat(lines):gsub('%%', '%%%%') .. 'END 7'))
end
local full = boxes(1024, -20.5, -8, -20.5)
assert(#full <= 65536 and #assert(Protocol.parse_terrain(full)).boxes == 1024, 'the whole box budget fits one packet')
reject(boxes(1025, -20.5, -8, -20.5))
-- The window as far as it may travel: the same packet size, and Minecraft's own coordinates back.
local far = boxes(1024, -2047.5, -1000, -2047.5)
assert(#far <= 65536 and #far - #full < 32, 'far windows write packets as short (only BOUNDS grows)')
local last = assert(Protocol.parse_terrain(far)).boxes[1024]
assert(table.concat(last, ' ') == '-2008.6875 -999.9375 -2008.6875 -2007.8125 -998.0625 -2007.8125', table.concat(last, ' '))
reject(boxes(1, -2048.5, -1000, -2047.5))

-- Heartbeats differ only in TIME and reuse the previous parse; anything else is parsed and validated again.
local cache = {}
local first = assert(Protocol.parse_terrain_cached(fixture, cache))
local beat = assert(Protocol.parse_terrain_cached((fixture:gsub('TIME 1791110000', 'TIME 1791110003')), cache))
assert(beat.time == 1791110003 and beat.boxes == first.boxes and beat.seq == 7, 'a heartbeat reuses the parsed geometry')
assert(first.time == 1791110000, 'a heartbeat never mutates the earlier packet')
for _, bad in ipairs({fixture:gsub('TIME 1791110000', 'TIME 9007199254740992'), fixture:gsub('6%.5', 'nan'),
    fixture:gsub('TIME 1791110000', 'TIME 1') .. string.rep('x', 65537)}) do
    assert(Protocol.parse_terrain_cached(bad, cache) == nil, 'a cached parse cannot accept what a full parse rejects')
end
assert(assert(Protocol.parse_terrain_cached((fixture:gsub('BOX 5%.5 2', 'BOX 6 2')), cache)).boxes[2][1] == 1.5)

assert(type(Protocol.parse_eve) == 'function', 'parse_eve must recover validated publication sequences')
local eve_fixture = read('protocol/fixtures/eve.txt')
local parsed_eve = assert(Protocol.parse_eve(eve_fixture))
assert(parsed_eve.session == terrain.session and parsed_eve.seq == 9 and parsed_eve.time == 1791110001)
assert(parsed_eve.state == 'entered' and parsed_eve.ack == 7)
assert(parsed_eve.position.x == -1.25 and parsed_eve.position.y == 0.05 and parsed_eve.position.z == 2)
for _, state in ipairs({'ready', 'idle'}) do
    assert(Protocol.parse_eve((eve_fixture:gsub('entered', state))).state == state)
end
assert(Protocol.parse_eve((eve_fixture:gsub('0%.05', '5e-2'))).position.y == 0.05)
local maximum = eve_fixture:gsub('SEQ 9', 'SEQ 2147483647'):gsub('END 9', 'END 2147483647')
assert(Protocol.parse_eve(maximum).seq == 2147483647)
local function reject_eve(text)
    local ok, value, reason = pcall(Protocol.parse_eve, text)
    assert(ok and value == nil and type(reason) == 'string', 'malformed EVE must return nil,error')
end
for _, bad in ipairs({
    eve_fixture:sub(1, -2), eve_fixture .. '\n', eve_fixture:gsub('\n', '\r\n'),
    eve_fixture:gsub('END 9', 'END 8'), eve_fixture:gsub('SEQ 9', 'SEQ 0'),
    eve_fixture:gsub('SEQ 9', 'SEQ 2147483648'), eve_fixture:gsub('entered 7', 'entered 0'),
    eve_fixture:gsub('entered 7', 'entered 2147483648'), eve_fixture:gsub('entered', 'flying'),
    eve_fixture:gsub('%-1%.25', 'nan'), eve_fixture:gsub('%-1%.25', '1e999'),
    eve_fixture:gsub('%-1%.25', '2048.001'), eve_fixture:gsub('%-1%.25', '0x1'),
    eve_fixture:gsub('SBMC 2', 'SBMC 1'),
    eve_fixture:gsub('SEQ ', 'SEQ  '), eve_fixture:gsub('SESSION 0', 'SESSION G'),
    eve_fixture:gsub('1791110001', '-1'), eve_fixture:gsub('1791110001', '9007199254740992'),
    eve_fixture:gsub('EVE', 'TERRAIN'), eve_fixture:gsub('POS', 'POS\0'),
    string.rep('x', 65537),
}) do reject_eve(bad) end
reject_eve(nil)
assert(Protocol.parse_eve((eve_fixture:gsub('%-1%.25', '-2048'))).position.x == -2048, 'EVE travels with the window')

local eve = {session=terrain.session, seq=9, time=1791110001, state='entered', ack=7,
    position={x=-1.25, y=0.05, z=2}}
local encoded = Protocol.encode_eve(eve)
assert(encoded:find('STATE entered 7\n', 1, true))
assert(encoded:sub(-6) == 'END 9\n')
assert(Protocol.parse_eve(encoded).position.y == 0.05)
for key, value in pairs({seq=0, ack=0, time=-1, state='flying', session='BAD'}) do
    local invalid = {}; for k,v in pairs(eve) do invalid[k]=v end
    invalid[key] = value
    assert(not pcall(Protocol.encode_eve, invalid), 'invalid EVE ' .. key .. ' must not serialize')
end
for _, value in ipairs({2048.001, math.huge, 0/0}) do
    eve.position.x=value
    assert(not pcall(Protocol.encode_eve, eve), 'invalid EVE position must not serialize')
end
eve.position.x=2047.75
assert(Protocol.parse_eve(Protocol.encode_eve(eve)).position.x == 2047.75)
eve.position.x=-1.25
assert(Protocol.fresh(100, 105) and Protocol.fresh(102, 100))
assert(not Protocol.fresh(100,106) and not Protocol.fresh(103,100))
assert(not Protocol.fresh(0/0,100) and not Protocol.fresh(nil,100))

if arg[1] then
    local java = assert(Protocol.parse_terrain(read(arg[1] .. '/terrain-java.txt')))
    assert(java.seq == 7 and java.origin.z == -0.5 and java.boxes[2][5] == 1.5)
    -- Java's relative boxes come back as the coordinates it rounded (a ten-thousandth), near and far.
    local far_java = assert(Protocol.parse_terrain(read(arg[1] .. '/terrain-far-java.txt')))
    assert(#far_java.boxes == 1024 and table.concat(far_java.boxes[1], ' ') == '-2047.4375 -999.9375 -2047.4375 -2046.5625 -998.0625 -2046.5625')
    local bamboo = assert(Protocol.parse_terrain(read(arg[1] .. '/terrain-bamboo-java.txt')))
    assert(table.concat(bamboo.boxes[1], ' ') == '-16.1771 -3.9 -16.2771 -16.0521 -2.9 -16.1521', table.concat(bamboo.boxes[1], ' '))
    local file = assert(io.open(arg[1] .. '/eve-lua.txt', 'wb'))
    file:write(Protocol.encode_eve(eve)); file:close()
end
print('PASS protocol Lua: valid terrain, malformed snapshots, EVE validation, freshness' ..
    (arg[1] and ', Java-to-Lua interoperability' or ''))
