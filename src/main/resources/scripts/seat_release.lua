-- seat_release.lua
-- 좌석 임시 점유 해제 Lua 스크립트
--
-- KEYS[1]: hold key      (예: {schedule:1001}:seat:12:hold:pending_abc123)
-- KEYS[2]: holds key     (예: {schedule:1001}:seat:12:holds) - Hold 목록 인덱스
-- KEYS[3]: holdIndexKey  (예: {schedule:1785}:traincar:231:holding-seats) - Hold Index
--
-- ARGV[1]: pendingBookingId (holds Set에서 제거할 값)
-- ARGV[2]: seatId
-- ARGV[3...]: sections (이 Hold가 점유했던 구간들)
--
-- 반환값:
-- 성공: {1, "RELEASE_SUCCESS"}
-- 키 없음: {1, "ALREADY_RELEASED"} (이미 만료되었거나 해제됨 - 성공으로 처리)

local holdKey = KEYS[1]
local holdsKey = KEYS[2]
local holdIndexKey = KEYS[3]
local pendingBookingId = ARGV[1]
local seatId = ARGV[2]

-- 해제 대상 구간 수집
local sections = {}
for i = 3, #ARGV do
    table.insert(sections, ARGV[i])
end

-- 1. Hold 키 삭제 (없어도 에러 아님)
local deleted = redis.call("DEL", holdKey)

-- 2. holds 인덱스에서 제거
redis.call("SREM", holdsKey, pendingBookingId)

-- 3. Hold Index 정리
for _, s in ipairs(sections) do
    local member = seatId .. ":" .. s
    redis.call("ZREM", holdIndexKey, member)
end

if deleted == 1 then
    return {1, "RELEASE_SUCCESS"}
else
    return {1, "ALREADY_RELEASED"}
end
