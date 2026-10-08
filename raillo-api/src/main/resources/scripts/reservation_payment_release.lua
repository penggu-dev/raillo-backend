-- reservation_payment_release.lua
-- 확정 실패 시 자기 예약 좌석 field의 만료를 보호 이전 상태로 되돌린다.
--
-- KEYS[1]    예약 본문 키            {schedule:1001}:reservation:RV...
-- KEYS[2]    주문 표시 키            {schedule:1001}:reservation:RV...:order
-- KEYS[3..]  객차 좌석 점유 Hash     {schedule:1001}:car:231:seats (중복 없음)
--
-- ARGV[1]    reservationId
-- ARGV[2]    출발 stopOrder
-- ARGV[3]    도착 stopOrder
-- ARGV[4..]  "seatId:carKeyIndex" - carKeyIndex는 KEYS[3..] 안에서 1부터 시작하는 순번
--
-- 되돌릴 기한은 주문 표시 키의 남은 TTL을 먼저 쓰고, 없으면 예약 본문 키의 남은 TTL을 쓴다.
-- 둘 다 없으면 되돌릴 상태가 없으므로 자기 field를 삭제한다.
-- 해제는 좌석을 버리는 것이 아니라 보호 이전으로 돌리는 것이다. 카드가 거절돼도 남은 시간 안에서는
-- 다른 카드로 다시 결제할 수 있어야 한다.
--
-- 자기 R:{reservationId}가 아닌 field는 건드리지 않는다. 남의 예약과 이미 확정된 예매를 지키기 위해서다.
--
-- 반환값
--   {restoredCount, deletedCount}

local reservedValue = "R:" .. ARGV[1]
local departureStopOrder = tonumber(ARGV[2])
local arrivalStopOrder = tonumber(ARGV[3])

-- 되돌릴 남은 TTL(초)을 정한다. TTL은 키가 없으면 -2, 만료가 없으면 -1을 돌려준다
-- 1초 미만이 남아 0이 오는 경우도 되돌릴 기한이 없는 것으로 다룬다
local remainingSeconds = redis.call("TTL", KEYS[2])
if remainingSeconds <= 0 then
    remainingSeconds = redis.call("TTL", KEYS[1])
end

-- 객차별로 다룰 field 목록을 만든다
local fieldsByCar = {}
for i = 4, #ARGV do
    local separator = string.find(ARGV[i], ":", 1, true)
    local seatId = string.sub(ARGV[i], 1, separator - 1)
    local carIndex = tonumber(string.sub(ARGV[i], separator + 1))

    local fields = fieldsByCar[carIndex]
    if fields == nil then
        fields = {}
        fieldsByCar[carIndex] = fields
    end
    for section = departureStopOrder, arrivalStopOrder - 1 do
        table.insert(fields, seatId .. ":" .. section)
    end
end

local restored = 0
local deleted = 0

for carIndex, fields in pairs(fieldsByCar) do
    local carKey = KEYS[carIndex + 2]
    local values = redis.call("HMGET", carKey, unpack(fields))

    -- 자기 예약이 점유한 field만 고른다
    local ownFields = {}
    for i, value in ipairs(values) do
        if value == reservedValue then
            table.insert(ownFields, fields[i])
        end
    end

    if #ownFields > 0 then
        if remainingSeconds > 0 then
            redis.call("HEXPIRE", carKey, remainingSeconds, "FIELDS", #ownFields, unpack(ownFields))
            restored = restored + #ownFields
        else
            redis.call("HDEL", carKey, unpack(ownFields))
            deleted = deleted + #ownFields
        end
    end
end

return {restored, deleted}
