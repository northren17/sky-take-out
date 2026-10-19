local activityKey = KEYS[1]
local userCountsKey = KEYS[2]
local requestsKey = KEYS[3]
local reconcileLockKey = KEYS[4]
local now = tonumber(ARGV[1])
local userId = ARGV[2]
local businessNo = ARGV[3]

if redis.call('EXISTS', reconcileLockKey) == 1 then
    return -7
end

local previousUser = redis.call('HGET', requestsKey, businessNo)
if previousUser then
    if previousUser == userId then
        return 2
    end
    return -6
end

local status = redis.call('HGET', activityKey, 'status')
local stock = tonumber(redis.call('HGET', activityKey, 'stockRemaining'))
local startTime = tonumber(redis.call('HGET', activityKey, 'startTime'))
local endTime = tonumber(redis.call('HGET', activityKey, 'endTime'))
local limit = tonumber(redis.call('HGET', activityKey, 'perUserLimit'))
if not status or not stock or not startTime or not endTime or not limit then
    return -5
end
if status ~= '1' then
    return -1
end
if now < startTime or now >= endTime then
    return -2
end
if stock <= 0 then
    return -3
end

local claimed = tonumber(redis.call('HGET', userCountsKey, userId) or '0')
if claimed >= limit then
    return -4
end

redis.call('HINCRBY', activityKey, 'stockRemaining', -1)
redis.call('HINCRBY', userCountsKey, userId, 1)
redis.call('HSET', requestsKey, businessNo, userId)
return 1
