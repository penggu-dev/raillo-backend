-- seat_confirm.lua
-- 좌석 확정 Lua 스크립트 (Hold → Sold 전환)
--
-- KEYS[1]: sold key      (예: {seat:1001:12}:sold)
-- KEYS[2]: hold key      (예: {seat:1001:12}:hold:pending_abc123)
--
-- 반환값:
-- 성공: {1, "CONFIRM_SUCCESS"}
-- 실패: {0, "HOLD_NOT_FOUND"}

local soldKey = KEYS[1]
local holdKey = KEYS[2]

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

-- 3. Hold 키 삭제
redis.call("DEL", holdKey)

return {1, "CONFIRM_SUCCESS"}
