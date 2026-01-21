-- seat_hold.lua
-- 좌석 임시 점유 Lua 스크립트
--
-- KEYS[1]: sold key      (예: {seat:1001:12}:sold)
-- KEYS[2]: new hold key  (예: {seat:1001:12}:hold:pending_abc123)
-- ARGV[1]: ttl (seconds)
-- ARGV[2...]: sections ("0-1", "1-2", ...)
--
-- 반환값:
-- 성공: {1, "HOLD_SUCCESS"}
-- 실패: {0, "CONFLICT_WITH_SOLD", "충돌구간"} 또는 {0, "CONFLICT_WITH_HOLD", "충돌구간"}

local soldKey = KEYS[1]
local newHoldKey = KEYS[2]
local ttl = tonumber(ARGV[1])

-- 요청 구간 수집
local sections = {}
for i = 2, #ARGV do
    table.insert(sections, ARGV[i])
end

-- 1. SOLD 충돌 검사 (이미 확정된 예약과 겹치는지)
for _, s in ipairs(sections) do
    if redis.call("SISMEMBER", soldKey, s) == 1 then
        return {0, "CONFLICT_WITH_SOLD", s}
    end
end

-- 2. HOLD 충돌 검사 (다른 사용자의 임시 점유와 겹치는지)
-- Hash Tag로 인해 같은 슬롯 내에서만 KEYS 검색
local pattern = string.gsub(newHoldKey, ":hold:.*", ":hold:*")
local holdKeys = redis.call("KEYS", pattern)

for _, hk in ipairs(holdKeys) do
    -- 자기 자신은 제외 (재시도 케이스 대응)
    if hk ~= newHoldKey then
        for _, s in ipairs(sections) do
            if redis.call("SISMEMBER", hk, s) == 1 then
                return {0, "CONFLICT_WITH_HOLD", s}
            end
        end
    end
end

-- 3. 임시 점유 생성
for _, s in ipairs(sections) do
    redis.call("SADD", newHoldKey, s)
end
redis.call("EXPIRE", newHoldKey, ttl)

return {1, "HOLD_SUCCESS"}
