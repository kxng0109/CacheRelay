-- Atomic fixed-window SPEND budget check, per subject level (KEY / TEAM / ORG).
--
-- Executed via Spring Data Redis DefaultRedisScript (EVALSHA; the NOSCRIPT
-- fallback to EVAL is handled transparently by Spring, not by this script).
-- Deliberately SEPARATE from rate_limit.lua: keys without budgets never invoke
-- it (zero overhead on the existing path), and the rate script's tested
-- semantics stay byte-identical.
--
-- FIN-B22: the window and month are derived from the Redis server clock
-- (TIME), never from a gateway instance's clock: all pods address the same
-- counter keys, so skew can neither split a window nor straddle a month.
-- Counter keys are built here from the level subjects; every key carries the
-- same hash tag the engine passes, so placement stays single-slot.
--
-- KEYS: config hashes only (level order KEY, TEAM, ORG).
--   An empty-string key means "level not configured" and is skipped entirely.
--   KEYS[1..3] = config hashes (HGET minute_micros / month_micros).
--
-- ARGV:
--   ARGV[1] = estimatedCostMicros integer >= 0; spend this request is expected to add
--   ARGV[2] = dedupe claim id (namespaced by the engine); '' means no dedupe
--   ARGV[3] = KEY-level subject (key sha256 hex)
--   ARGV[4] = TEAM-level subject (owner id); '' skips the level
--   ARGV[5] = ORG-level subject
--   ARGV[6] = hash tag shared by every budget key (e.g. {b:global})
--
-- Semantics (the anti-double-charge rules that motivated this shape):
--   * CHECK-BEFORE-INCREMENT: a request that would exceed a cap is rejected
--     WITHOUT touching any counter. Post-increment checks (like the rate
--     limiter's attempt counting) would let denied requests inflate spend and
--     re-exhaust budgets — the exact reservation-leak class seen in production
--     gateways. Unlimited dimensions (limit == 0) and zero-cost requests never
--     create keys.
--   * Denial precedence is KEY, then TEAM, then ORG; minute before month inside
--     a level. The first violated dimension wins and later levels are not
--     evaluated (their spend is untouched by a request that will not run).
--   * Month counters carry a 45-day safety TTL (longest month plus margin);
--     calendar rollover is implicit because the engine addresses a new
--     YYYY-MM key each month — there is no reset job to race.
--   * Counters and limits are micro-dollars (integers); floating point never
--     crosses this boundary.
--
-- Return value: the 5 historic integers (never booleans; a Lua false inside a
-- returned table collapses to nil and truncates the array) plus the
-- authoritative calendar month the charge landed in:
--   [1] allowed          1 = within every cap, 0 = rejected
--   [2] rejected         0 = none, 1 = key-minute, 2 = key-month,
--                        3 = team-minute, 4 = team-month, 5 = org-minute, 6 = org-month
--   [3] remainingMicros  lowest (limit - count) across evaluated dims, clamped >= 0;
--                        -1 when no dimension was configured at all
--   [4] resetSeconds     minute-window TTL of the binding dim; 0 when the binding
--                        dim is monthly
--   [5] configured       count of dimensions with a limit > 0 (lets the engine
--                        cache presence without a second round trip)
--   [6] yearMonth        "YYYY-MM" derived from server TIME (the engine records
--                        holds against this month so settlement addresses the
--                        same counter the charge incremented)

-- Authoritative window from the server clock (TIME returns {seconds, micros}).
local timeParts = redis.call('TIME')
local epochSec = tonumber(timeParts[1])
local epochMinute = math.floor(epochSec / 60)

-- Proleptic Gregorian YYYY-MM from epoch seconds (Howard Hinnant's
-- days-to-civil algorithm; epoch is always positive here, so era is exact).
-- Source: http://howardhinnant.github.io/date_algorithms.html
local function serverYearMonth(sec)
	local days = math.floor(sec / 86400)
	local z = days + 719468
	local era = math.floor(z / 146097)
	local doe = z - era * 146097
	local yoe = math.floor((doe - math.floor(doe / 1460)
		+ math.floor(doe / 36524) - math.floor(doe / 146096)) / 365)
	local y = yoe + era * 400
	local doy = doe - (365 * yoe + math.floor(yoe / 4) - math.floor(yoe / 100))
	local mp = math.floor((5 * doy + 2) / 153)
	local m = mp + 3
	if m > 12 then
		m = m - 12
		y = y + 1
	end
	return string.format('%04d-%02d', y, m)
end
local yearMonth = serverYearMonth(epochSec)

local estimated = tonumber(ARGV[1])
if estimated == nil or estimated < 0 then
	estimated = 0
end

local slotTag = ARGV[6]
local subjects = { ARGV[3], ARGV[4], ARGV[5] }
local levelNames = { 'KEY', 'TEAM', 'ORG' }

-- Counts configured dimensions exactly like the main loop (minute>0 and month>0
-- each count once), for the early-return paths below that must report an honest
-- configured count so the engine's presence cache is never poisoned by a zero.
local function countConfigured()
	local n = 0
	local lvl = 1
	while lvl <= 3 do
		local ck = KEYS[lvl]
		if ck ~= '' and subjects[lvl] ~= '' then
			if (tonumber(redis.call('HGET', ck, 'minute_micros') or '0') or 0) > 0 then
				n = n + 1
			end
			if (tonumber(redis.call('HGET', ck, 'month_micros') or '0') or 0) > 0 then
				n = n + 1
			end
		end
		lvl = lvl + 1
	end
	return n
end

-- Lua numbers are doubles: integers above 2^53-1 compare and accumulate
-- inexactly, so a corrupt estimate could overflow a counter or misjudge a cap.
-- The engine clamps to this bound, so this is defense-in-depth only: deny without
-- consuming (first-denied-wins is preserved trivially — nothing was evaluated),
-- counting configured dimensions exactly like the main loop so the presence cache
-- is not poisoned by a zero count.
local MAX_EXACT_INTEGER = 9007199254740991
if estimated > MAX_EXACT_INTEGER then
	return { 0, 1, 0, 60, countConfigured(), yearMonth }
end

-- Idempotency: a retried client key must not double-debit when the first flight
-- already stored its completion (served from the replay store before reaching
-- this gate), and concurrent duplicates are fenced by the replay fill lock.
-- The claim records every attempt (namespaced by the engine as
-- tenant:subject:bodyHash:key; 24h Stripe-style lifecycle) but NEVER admits
-- for free: a duplicate claim with no replay hit is retried upstream work, so
-- it falls through to full cap evaluation and charging (fail-closed). Absent
-- id means no dedupe (internal callers, legacy clients). Client keys are
-- length- and charset-bounded at the controllers (<= 255 printable ASCII), so
-- the composed key cannot bloat the keyspace per call.
local dedupeId = ARGV[2]
if dedupeId ~= nil and dedupeId ~= '' then
	local dedupeKey = 'budget:{b:global}:dedupe:' .. dedupeId
	redis.call('SET', dedupeKey, '1', 'NX', 'EX', 86400)
end

local rejected = 0
local remaining = -1
local resetSeconds = 0
local configured = 0
local level = 1
while level <= 3 do
	local cfgKey = KEYS[level]
	local subject = subjects[level]
	if cfgKey ~= '' and subject ~= '' then
		local minuteLimit = tonumber(redis.call('HGET', cfgKey, 'minute_micros') or '0') or 0
		local monthLimit = tonumber(redis.call('HGET', cfgKey, 'month_micros') or '0') or 0
		if minuteLimit > 0 then
			configured = configured + 1
		end
		if monthLimit > 0 then
			configured = configured + 1
		end
		if minuteLimit > 0 or monthLimit > 0 then
			local minuteKey = 'budget:' .. slotTag .. ':' .. levelNames[level] .. ':' .. subject
				.. ':minute:' .. epochMinute
			local monthKey = 'budget:' .. slotTag .. ':' .. levelNames[level] .. ':' .. subject
				.. ':month:' .. yearMonth
			local minuteCount = tonumber(redis.call('GET', minuteKey) or '0') or 0
			local monthCount = tonumber(redis.call('GET', monthKey) or '0') or 0
			local minuteDenied = minuteLimit > 0 and minuteCount + estimated > minuteLimit
			local monthDenied = monthLimit > 0 and monthCount + estimated > monthLimit
			if minuteDenied or monthDenied then
				rejected = (level - 1) * 2 + 1
				resetSeconds = 60
				if minuteDenied then
					local ttl = redis.call('TTL', minuteKey)
					if ttl ~= nil and ttl > 0 then
						resetSeconds = ttl
					end
				else
					rejected = (level - 1) * 2 + 2
					resetSeconds = 0
				end
				remaining = 0
				break
			end
			if estimated > 0 then
				if minuteLimit > 0 then
					minuteCount = redis.call('INCRBY', minuteKey, estimated)
					local ttl = redis.call('TTL', minuteKey)
					if ttl == nil or ttl < 0 then
						redis.call('EXPIRE', minuteKey, 60)
						ttl = 60
					end
				end
				if monthLimit > 0 then
					monthCount = redis.call('INCRBY', monthKey, estimated)
					if redis.call('TTL', monthKey) < 0 then
						redis.call('EXPIRE', monthKey, 3888000)
					end
				end
			end
			if minuteLimit > 0 then
				local left = minuteLimit - minuteCount
				if remaining < 0 or left < remaining then
					remaining = left
					local ttl = redis.call('TTL', minuteKey)
					if ttl == nil or ttl < 0 then
						ttl = 60
					end
					resetSeconds = ttl
				end
			end
			if monthLimit > 0 then
				local left = monthLimit - monthCount
				if remaining < 0 or left < remaining then
					remaining = left
					resetSeconds = 0
				end
			end
		end
	end
	level = level + 1
end

local allowed = 1
if rejected > 0 then
	allowed = 0
end

return { allowed, rejected, remaining, resetSeconds, configured, yearMonth }
