-- reservation_booking_confirm.lua
-- 결제가 확정된 예약의 좌석 점유를 예매 점유로 바꾸고 예약 본문을 지운다.
--
-- KEYS[1]    예약 키                {schedule:1001}:reservation:RV...
-- KEYS[2..]  객차 좌석 점유 Hash    {schedule:1001}:car:231:seats (중복 없음)
--
-- ARGV[1]    reservationId
-- ARGV[2]    bookingId
-- ARGV[3]    객차 Hash 키의 만료 시각 (Unix epoch 초). 키에 만료가 없을 때만 건다
-- ARGV[4]    출발 stopOrder
-- ARGV[5]    도착 stopOrder
-- ARGV[6..]  "seatId:carKeyIndex" - carKeyIndex는 KEYS[2..] 안에서 1부터 시작하는 순번
--
-- field 값별 처리
--   R:{reservationId}  B:{bookingId}로 바꾸고 field 만료를 없앤다
--   B:{bookingId}      이미 전환됐다. 값은 그대로 두고 만료만 다시 없앤다
--   없음               B:{bookingId}를 쓴다. 호출자는 DB 예매가 유효할 때만 이 스크립트를 부른다
--   그 외              충돌. 아무것도 쓰지 않는다
--
-- 반환값
--   {1}                                성공
--   {0, seatId, sectionIndex, "R"}     다른 예약이 점유 중
--   {0, seatId, sectionIndex, "B"}     다른 예매가 점유 중
--   {0, seatId, sectionIndex, "X"}     알 수 없는 값 형식 (데이터 오염)

local reservationKey = KEYS[1]
local reservedValue = "R:" .. ARGV[1]
local bookedValue = "B:" .. ARGV[2]
local keyExpireAt = tonumber(ARGV[3])
local departureStopOrder = tonumber(ARGV[4])
local arrivalStopOrder = tonumber(ARGV[5])

-- 객차별로 전환할 field 목록을 만든다
local fieldsByCar = {}
for i = 6, #ARGV do
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

-- 1. 검사: 자기 예약, 자기 예매, 빈 field가 아닌 값이 하나라도 있으면 즉시 반환
--    HMGET의 없는 field는 Lua에서 false로 오므로 ipairs가 끊기지 않는다
for carIndex, fields in pairs(fieldsByCar) do
    local values = redis.call("HMGET", KEYS[carIndex + 1], unpack(fields))

    for i, value in ipairs(values) do
        if value and value ~= reservedValue and value ~= bookedValue then
            local field = fields[i]
            local separator = string.find(field, ":", 1, true)
            local seatId = string.sub(field, 1, separator - 1)
            local section = tonumber(string.sub(field, separator + 1))
            local prefix = string.sub(value, 1, 2)

            if prefix == "R:" then
                return {0, seatId, section, "R"}
            elseif prefix == "B:" then
                return {0, seatId, section, "B"}
            else
                return {0, seatId, section, "X"}
            end
        end
    end
end

-- 2. 쓰기: 예매 점유로 바꾸고, field 만료를 없애고, 키 만료가 없으면 운행일 만료를 건다
for carIndex, fields in pairs(fieldsByCar) do
    local carKey = KEYS[carIndex + 1]

    local hsetArgs = {}
    for _, field in ipairs(fields) do
        table.insert(hsetArgs, field)
        table.insert(hsetArgs, bookedValue)
    end
    redis.call("HSET", carKey, unpack(hsetArgs))
    redis.call("HPERSIST", carKey, "FIELDS", #fields, unpack(fields))

    if redis.call("TTL", carKey) == -1 then
        redis.call("EXPIREAT", carKey, keyExpireAt)
    end
end

redis.call("DEL", reservationKey)

return {1}
