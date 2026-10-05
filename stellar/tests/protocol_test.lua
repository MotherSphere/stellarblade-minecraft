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
assert(#terrain.boxes == 2 and terrain.boxes[2][5] == 1.5)
assert(Protocol.parse_terrain((fixture:gsub('1%.5', '1.5e0'))))
for _, bad in ipairs({
    fixture:sub(1, -2), fixture .. '\n', fixture:gsub('\n', '\r\n'),
    fixture:gsub('END 7', 'END 8'), fixture:gsub('SEQ 7', 'SEQ 0'),
    fixture:gsub('SEQ 7', 'SEQ 2147483648'), fixture:gsub('COUNT 2', 'COUNT 257'),
    fixture:gsub('COUNT 2', 'COUNT 1'), fixture:gsub('1%.5', 'nan'),
    fixture:gsub('1%.5', '1e999'), fixture:gsub('BOX 1 0', 'BOX 2 0'),
    fixture:gsub('BOX 1 0', 'BOX -5 0'), fixture:gsub('64 %-0.5', '4097 -0.5'),
    fixture:gsub('minecraft:overworld', 'Minecraft:overworld'),
    fixture:gsub('SESSION 0', 'SESSION G'), fixture:gsub('SEQ ', 'SEQ  '),
    fixture:gsub('1%.5', '0x1'), fixture:gsub('TIME 1791110000', 'TIME -1'),
    fixture:gsub('TIME 1791110000', 'TIME 9007199254740992'),
    string.rep('x', 65537),
}) do reject(bad) end
reject(nil)

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
    eve_fixture:gsub('%-1%.25', '65'), eve_fixture:gsub('%-1%.25', '0x1'),
    eve_fixture:gsub('SEQ ', 'SEQ  '), eve_fixture:gsub('SESSION 0', 'SESSION G'),
    eve_fixture:gsub('1791110001', '-1'), eve_fixture:gsub('1791110001', '9007199254740992'),
    eve_fixture:gsub('EVE', 'TERRAIN'), eve_fixture:gsub('POS', 'POS\0'),
    string.rep('x', 65537),
}) do reject_eve(bad) end
reject_eve(nil)

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
for _, value in ipairs({65, math.huge, 0/0}) do
    eve.position.x=value
    assert(not pcall(Protocol.encode_eve, eve), 'invalid EVE position must not serialize')
end
eve.position.x=-1.25
assert(Protocol.fresh(100, 105) and Protocol.fresh(102, 100))
assert(not Protocol.fresh(100,106) and not Protocol.fresh(103,100))
assert(not Protocol.fresh(0/0,100) and not Protocol.fresh(nil,100))

if arg[1] then
    local java = assert(Protocol.parse_terrain(read(arg[1] .. '/terrain-java.txt')))
    assert(java.seq == 7 and java.origin.z == -0.5 and java.boxes[2][5] == 1.5)
    local file = assert(io.open(arg[1] .. '/eve-lua.txt', 'wb'))
    file:write(Protocol.encode_eve(eve)); file:close()
end
print('PASS protocol Lua: valid terrain, malformed snapshots, EVE validation, freshness' ..
    (arg[1] and ', Java-to-Lua interoperability' or ''))
