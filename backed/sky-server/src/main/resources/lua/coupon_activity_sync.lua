local activityKey = KEYS[1]
local userCountsKey = KEYS[2]
local requestsKey = KEYS[3]
local reconcileLockKey = KEYS[4]
local status = ARGV[1]
local stockRemaining = ARGV[2]
local stockTotal = ARGV[3]
local startTime = ARGV[4]
local endTime = ARGV[5]
local perUserLimit = ARGV[6]
local updateStock = ARGV[7]

if redis.call('EXISTS', reconcileLockKey) == 1 then
    return -3
end
if updateStock == '1' and redis.call('HLEN', requestsKey) > 0 then
    return -2
end

if redis.call('EXISTS', activityKey) == 0 then
    redis.call('HSET', activityKey,
        'status', status,
        'stockRemaining', stockRemaining,
        'stockTotal', stockTotal,
        'startTime', startTime,
        'endTime', endTime,
        'perUserLimit', perUserLimit)
    return 1
end

if redis.call('HEXISTS', activityKey, 'stockRemaining') == 0 or
        redis.call('HEXISTS', activityKey, 'stockTotal') == 0 then
    return -1
end

redis.call('HSET', activityKey,
    'status', status,
    'startTime', startTime,
    'endTime', endTime,
    'perUserLimit', perUserLimit)
if updateStock == '1' then
    redis.call('HSET', activityKey,
        'stockRemaining', stockRemaining,
        'stockTotal', stockTotal)
end
return 1
