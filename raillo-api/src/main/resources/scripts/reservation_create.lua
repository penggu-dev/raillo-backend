-- reservation_create.lua
-- 좌석 점유 검사와 예약 생성을 원자적으로 처리한다.
--
-- KEYS[1]    예약 키                {schedule:1001}:reservation:RV...
-- KEYS[2..]  객차 좌석 점유 Hash    {schedule:1001}:car:231:seats (중복 없음)
--
-- ARGV[1]    reservationId
-- ARGV[2]    ttl (초, 1 이상) - 점유 field와 예약 키에 같이 적용
-- ARGV[3]    객차 Hash 키의 만료 시각 (Unix epoch 초). 키에 만료가 없을 때만 건다
-- ARGV[4]    예약 JSON
-- ARGV[5]    출발 stopOrder
-- ARGV[6]    도착 stopOrder
-- ARGV[7..]  "seatId:carKeyIndex" - carKeyIndex는 KEYS[2..] 안에서 1부터 시작하는 순번
--
-- 반환값
--   {1}                                성공
--   {0, seatId, sectionIndex, "R"}     다른 예약이 점유 중인 구간
--   {0, seatId, sectionIndex, "B"}     이미 예매된 구간
--   {0, seatId, sectionIndex, "X"}     알 수 없는 값 형식 (데이터 오염)
-- 충돌 시 아무것도 쓰지 않는다.

local reservationKey = KEYS[1]
local reservationId = ARGV[1]
local ttl = tonumber(ARGV[2])
local keyExpireAt = tonumber(ARGV[3])
local reservationJson = ARGV[4]
local departureStopOrder = tonumber(ARGV[5])
local arrivalStopOrder = tonumber(ARGV[6])
local reservedValue = "R:" .. reservationId

-- 객차별로 점유할 field 목록을 만든다
local fieldsByCar = {}
for i = 7, #ARGV do
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

-- 1. 검사: 요청 구간에 다른 점유가 있으면 즉시 반환
--    HMGET의 없는 field는 Lua에서 false로 오므로 ipairs가 끊기지 않는다
for carIndex, fields in pairs(fieldsByCar) do
    local carKey = KEYS[carIndex + 1]
    local values = redis.call("HMGET", carKey, unpack(fields))

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

-- 2. 쓰기: 예약 점유 field 생성, field 만료, 키 만료(최초 1회), 예약 저장
for carIndex, fields in pairs(fieldsByCar) do
    local carKey = KEYS[carIndex + 1]

    local hsetArgs = {}
    for _, field in ipairs(fields) do
        table.insert(hsetArgs, field)
        table.insert(hsetArgs, reservedValue)
    end
    redis.call("HSET", carKey, unpack(hsetArgs))
    redis.call("HEXPIRE", carKey, ttl, "FIELDS", #fields, unpack(fields))

    if redis.call("TTL", carKey) == -1 then
        redis.call("EXPIREAT", carKey, keyExpireAt)
    end
end

redis.call("SET", reservationKey, reservationJson, "EX", ttl)

return {1}
