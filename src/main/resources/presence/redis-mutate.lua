-- All room keys share a table hash tag. Redis TIME owns lease expiry.
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local guard = presenceRecovery(KEYS, tonumber(ARGV[5]), ARGV[9], now)
local rooms = #KEYS - 1
-- Validate every pair before changing either record or table membership.
for i = 1, rooms, 2 do
   if redis.call('ZCARD', KEYS[i]) ~= redis.call('HLEN', KEYS[i + 1]) then
      return redis.error_reply('Presence room state is incomplete')
   end
end
local member = ARGV[1]
local ttl = tonumber(ARGV[5])
local capacity = tonumber(ARGV[6])
local sequence = tonumber(ARGV[3])
local departed = ARGV[4] == '1'

for i = 1, rooms, 2 do
   local expired = redis.call('ZRANGEBYSCORE', KEYS[i], '-inf', now)
   for _, id in ipairs(expired) do
      redis.call('ZREM', KEYS[i], id)
      redis.call('HDEL', KEYS[i + 1], id)
   end
end

local previous = redis.call('HGET', KEYS[2], member)
-- A record lease and its table projection are written atomically. If one is
-- missing while the other is live, do not erase surviving ordering/tombstones.
if rooms == 4 then
   local tablePrevious = redis.call('HGET', KEYS[4], member)
   if (previous and not tablePrevious) or (tablePrevious and not previous) then
      return redis.error_reply('Presence room state is incomplete')
   end
end
local old = previous and cjson.decode(previous) or nil
local operation = ARGV[8] or 'legacy'
-- Renewal authority is issued by open, not inferred from a caller's sequence.
-- Missing/expired/departed leases cannot be recreated by delayed HTTP requests.
if operation == 'renew' and (not old or not old.leased or old.departed or old.generation ~= guard.generation) then
   return -1
end
if operation == 'open' and old then return -1 end
if operation == 'legacy' and not departed and old and old.leased then return -1 end
if old then
   local oldSequence = old.sequence
   if oldSequence == cjson.null then oldSequence = nil end
   if oldSequence then
      if not sequence or sequence < oldSequence or (sequence == oldSequence and (not departed or old.departed)) then
         return 1
      end
   elseif not sequence and old.at == now and old.departed and not departed then
      return 1
   end
end

-- Check every room before writing any membership, avoiding a partial join.
for i = 1, rooms, 2 do
   if redis.call('HEXISTS', KEYS[i + 1], member) == 0 and redis.call('ZCARD', KEYS[i]) >= capacity then
      return 0
   end
end

local data = cjson.decode(ARGV[2])
data.at = now
data.sequence = sequence or cjson.null
data.departed = departed
data.pending = operation == 'open'
data.leased = operation ~= 'legacy' or (old and old.leased) or false
data.generation = guard.generation
for i = 1, rooms, 2 do
   -- Record scope comes first; table scope must never carry focused-field data.
   if i > 1 or ARGV[7] == '1' then data.fieldName = cjson.null end
   redis.call('HSET', KEYS[i + 1], member, cjson.encode(data))
   redis.call('ZADD', KEYS[i], now + ttl, member)
   redis.call('PEXPIRE', KEYS[i], ttl)
   redis.call('PEXPIRE', KEYS[i + 1], ttl)
end
guard.liveUntil = now + ttl
redis.call('SET', KEYS[#KEYS], cjson.encode(guard))
return 1
