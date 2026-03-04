-- seat_release.lua
-- Seat Hold 해제 Lua 스크립트
--
-- KEYS[1]: Seat Hold 키           (예: {schedule:1001}:seat:12:hold:pending_abc123)
-- KEYS[2]: Seat Hold Index 키     (예: {schedule:1001}:seat:12:holds)
-- KEYS[3]: TrainCar Hold Index 키 (예: {schedule:1785}:traincar:231:holding-seats)
--
-- ARGV[1]: pendingBookingId (Seat Hold Index에서 제거할 값)
-- ARGV[2]: seatId
-- ARGV[3...]: sections (이 Seat Hold가 점유했던 구간들)
--
-- 반환값:
-- 성공: {1, "RELEASE_SUCCESS"}
-- 키 없음: {1, "ALREADY_RELEASED"} (이미 만료되었거나 해제됨 - 성공으로 처리)

local seatHoldKey = KEYS[1]
local seatHoldIndexKey = KEYS[2]
local trainCarHoldIndexKey = KEYS[3]
local pendingBookingId = ARGV[1]
local seatId = ARGV[2]

-- 해제 대상 구간 수집
local sections = {}
for i = 3, #ARGV do
    table.insert(sections, ARGV[i])
end

-- 1. Seat Hold 키 삭제 (없어도 에러 아님)
local deleted = redis.call("DEL", seatHoldKey)

-- 2. Seat Hold Index에서 제거
redis.call("SREM", seatHoldIndexKey, pendingBookingId)

-- 3. TrainCar Hold Index 정리
for _, s in ipairs(sections) do
    local member = seatId .. ":" .. s
    redis.call("ZREM", trainCarHoldIndexKey, member)
end

if deleted == 1 then
    return {1, "RELEASE_SUCCESS"}
else
    return {1, "ALREADY_RELEASED"}
end
