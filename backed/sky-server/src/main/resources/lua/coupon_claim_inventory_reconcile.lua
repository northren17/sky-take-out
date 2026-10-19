local lockToken = redis.call('GET', KEYS[3])
if not lockToken or lockToken ~= ARGV[1] then
    return -1
end

local stock = tonumber(ARGV[2])
if not stock or stock < 0 then
    return -2
end
redis.call('HSET', KEYS[1], 'stockRemaining', stock)
redis.call('DEL', KEYS[2])
for index = 3, #ARGV, 2 do
    redis.call('HSET', KEYS[2], ARGV[index], ARGV[index + 1])
end
redis.call('DEL', KEYS[3])
return 1
