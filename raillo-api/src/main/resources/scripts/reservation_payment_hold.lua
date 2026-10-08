-- reservation_payment_hold.lua
-- 결제 결과를 모르는 동안 자기 예약의 좌석 field를 붙잡는다.
--
-- KEYS[1..]  객차 좌석 점유 Hash    {schedule:1001}:car:231:seats (중복 없음)
--
-- ARGV[1]    reservationId
-- ARGV[2]    객차 Hash 키의 만료 시각 (Unix epoch 초). 키에 만료가 없을 때만 건다
-- ARGV[3]    출발 stopOrder
-- ARGV[4]    도착 stopOrder
-- ARGV[5..]  "seatId:carKeyIndex" - carKeyIndex는 KEYS[1..] 안에서 1부터 시작하는 순번
--
-- field 값별 처리
--   R:{reservationId}  만료를 없앤다(HPERSIST)
--   없음               R:{reservationId}를 쓰고 만료를 없앤다 (보호가 풀린 뒤의 재점유)
--   그 외              충돌. 아무것도 쓰지 않는다
--
-- 예약 본문과 회원 인덱스는 건드리지 않는다. 보호 대상은 좌석 field뿐이다.
--
-- 반환값
--   {1}                                성공
--   {0, seatId, sectionIndex, "R"}     다른 예약이 점유 중
--   {0, seatId, sectionIndex, "B"}     예매가 점유 중
--   {0, seatId, sectionIndex, "X"}     알 수 없는 값 형식 (데이터 오염)

local reservedValue = "R:" .. ARGV[1]
local keyExpireAt = tonumber(ARGV[2])
local departureStopOrder = tonumber(ARGV[3])
local arrivalStopOrder = tonumber(ARGV[4])

-- 객차별로 다룰 field 목록을 만든다
local fieldsByCar = {}
for i = 5, #ARGV do
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

-- 1. 검사: 자기 예약도 빈 field도 아닌 값이 하나라도 있으면 즉시 반환
for carIndex, fields in pairs(fieldsByCar) do
    local values = redis.call("HMGET", KEYS[carIndex], unpack(fields))

    for i, value in ipairs(values) do
        if value and value ~= reservedValue then
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

-- 2. 쓰기: 빠진 field를 채우고 전부 만료를 없앤다
for carIndex, fields in pairs(fieldsByCar) do
    local carKey = KEYS[carIndex]

    local hsetArgs = {}
    for _, field in ipairs(fields) do
        table.insert(hsetArgs, field)
        table.insert(hsetArgs, reservedValue)
    end
    redis.call("HSET", carKey, unpack(hsetArgs))
    redis.call("HPERSIST", carKey, "FIELDS", #fields, unpack(fields))

    if redis.call("TTL", carKey) == -1 then
        redis.call("EXPIREAT", carKey, keyExpireAt)
    end
end

return {1}
