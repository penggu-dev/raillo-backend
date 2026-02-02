-- seat_confirm.lua
-- 좌석 확정 Lua 스크립트 (Hold → Sold 전환)
--
-- KEYS[1]: sold key      (예: {seat:1001:12}:sold)
-- KEYS[2]: hold key      (예: {seat:1001:12}:hold:pending_abc123)
-- KEYS[3]: holds key     (예: {seat:1001:12}:holds) - Hold 목록 인덱스
-- ARGV[1]: pendingBookingId (holds Set에서 제거할 값)
-- ARGV[2]: soldTTL (열차 도착 시간 기반 TTL, 초 단위)
--
-- 반환값:
-- 성공: {1, "CONFIRM_SUCCESS"}
-- 실패: {0, "HOLD_NOT_FOUND"}

local soldKey = KEYS[1]
local holdKey = KEYS[2]
local holdsKey = KEYS[3]
local pendingBookingId = ARGV[1]
local soldTTL = tonumber(ARGV[2])

-- 1. Hold 키 존재 확인
local exists = redis.call("EXISTS", holdKey)
if exists == 0 then
    return {0, "HOLD_NOT_FOUND"}
end

-- 2. Hold의 구간들을 Sold로 이동
local sections = redis.call("SMEMBERS", holdKey)
for _, s in ipairs(sections) do
    redis.call("SADD", soldKey, s)
end

-- 3. Sold 키에 TTL 설정 (열차 도착 시간 + 여유분)
redis.call("EXPIRE", soldKey, soldTTL)

-- 4. Hold 키 삭제
redis.call("DEL", holdKey)

-- 5. holds 인덱스에서 제거
redis.call("SREM", holdsKey, pendingBookingId)

return {1, "CONFIRM_SUCCESS"}
