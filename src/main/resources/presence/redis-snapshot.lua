-- A partially evicted room is unavailable, not empty. Do not silently discard it.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local guard = presenceRecovery(KEYS, tonumber(ARGV[2]), ARGV[3], now)
local rooms = #KEYS - 1
for i = 1, rooms, 2 do
   if redis.call('ZCARD', KEYS[i]) ~= redis.call('HLEN', KEYS[i + 1]) then
      return redis.error_reply('Presence room state is incomplete')
   end
end
for i = 1, rooms, 2 do
   local expired = redis.call('ZRANGEBYSCORE', KEYS[i], '-inf', now)
   for _, id in ipairs(expired) do
      redis.call('ZREM', KEYS[i], id)
      redis.call('HDEL', KEYS[i + 1], id)
   end
end
local members = redis.call('ZRANGE', KEYS[1], 0, -1)
-- Table membership is bounded by the configured capacity. Compare the record's
-- projection without key discovery or cross-slot reads.
if rooms == 4 then
   local projected = 0
   for _, id in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do
      if string.sub(id, -string.len(ARGV[1])) == ARGV[1] then
         projected = projected + 1
         if not redis.call('ZSCORE', KEYS[1], id) then
            return redis.error_reply('Presence room state is incomplete')
         end
      end
   end
   if projected ~= #members then
      return redis.error_reply('Presence room state is incomplete')
   end
end
local result = {}
if now < guard.readyAt then return -2 end
for _, id in ipairs(members) do
   local data = redis.call('HGET', KEYS[2], id)
   if not data then return redis.error_reply('Presence room state is incomplete') end
   if cjson.decode(data).generation ~= guard.generation then
      return redis.error_reply('Presence generation is inconsistent')
   end
   table.insert(result, data)
end
return result
