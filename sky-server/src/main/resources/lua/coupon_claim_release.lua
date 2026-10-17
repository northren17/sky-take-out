local activityKey = KEYS[1]
local userCountsKey = KEYS[2]
local requestsKey = KEYS[3]
local userId = ARGV[1]
local businessNo = ARGV[2]
local mode = ARGV[3]

local previousUser = redis.call('HGET', requestsKey, businessNo)
if not previousUser then
    return 0
end
if previousUser ~= userId then
    return -1
end

if mode == 'compensate' then
    local stock = tonumber(redis.call('HGET', activityKey, 'stockRemaining'))
    local total = tonumber(redis.call('HGET', activityKey, 'stockTotal'))
    local claimed = tonumber(redis.call('HGET', userCountsKey, userId) or '0')
    if not stock or not total or stock < 0 or stock > total then
        return -2
    end
    if stock < total then
        redis.call('HINCRBY', activityKey, 'stockRemaining', 1)
    end
    if claimed > 0 then
        local remainingClaims = redis.call('HINCRBY', userCountsKey, userId, -1)
        if remainingClaims <= 0 then
            redis.call('HDEL', userCountsKey, userId)
        end
    end
    local result = (stock < total and claimed > 0) and 1 or 2
    redis.call('HDEL', requestsKey, businessNo)
    return result
end

redis.call('HDEL', requestsKey, businessNo)
return 1
