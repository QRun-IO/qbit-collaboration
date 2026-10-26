-- This prelude is executed atomically with the room operation, in the same slot.
-- The guard deliberately outlives idle leases: missing means unknown, not empty.
local function presenceRecovery(keys, ttl, candidate, now)
   local encoded = redis.call('GET', keys[#keys])
   local guard
   if encoded then
      guard = cjson.decode(encoded)
      if type(guard.generation) ~= 'string' or type(guard.readyAt) ~= 'number' or type(guard.liveUntil) ~= 'number' or type(guard.ttl) ~= 'number' then
         error('Invalid presence recovery guard')
      end
      if guard.ttl ~= ttl then error('Presence lease lifetime differs across nodes') end
      -- All volatile projections disappearing before the last issued expiry is
      -- data loss too, even when a persistent guard survives an eviction policy.
      if guard.liveUntil > now and redis.call('ZCARD', keys[#keys - 2]) == 0
         and redis.call('HLEN', keys[#keys - 1]) == 0 then
         guard = nil
      end
   end
   if not guard then
      guard = { generation = candidate, readyAt = now + ttl, liveUntil = 0, ttl = ttl }
      redis.call('SET', keys[#keys], cjson.encode(guard))
   end
   -- During recovery, discard only projections from an invalidated generation.
   -- This also frees capacity for new reservations without waiting for old TTLs.
   if now < guard.readyAt then
      for i = 1, #keys - 1, 2 do
         for _, member in ipairs(redis.call('HKEYS', keys[i + 1])) do
            local data = cjson.decode(redis.call('HGET', keys[i + 1], member))
            if data.generation ~= guard.generation then
               redis.call('HDEL', keys[i + 1], member)
               redis.call('ZREM', keys[i], member)
            end
         end
      end
   end
   return guard
end
