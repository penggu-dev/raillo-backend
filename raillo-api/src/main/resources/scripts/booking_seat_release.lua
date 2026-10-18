-- booking_seat_release.lua
-- Booking이 잡은 좌석 점유를 해제한다.
--
-- KEYS[1..]  객차 좌석 점유 Hash    {schedule:1001}:car:231:seats (중복 없음)
--
-- ARGV[1]    bookingId
-- ARGV[2]    출발 stopOrder
-- ARGV[3]    도착 stopOrder
-- ARGV[4..]  "seatId:carKeyIndex" - carKeyIndex는 KEYS[1..] 안에서 1부터 시작하는 순번
--
-- 반환값  {해제한 field 수, 값이 있었지만 자기 것이 아니었던 field 수}
-- 값이 정확히 "B:{bookingId}"인 field만 지운다. 다른 Booking의 B:와 Reservation의 R:은 건드리지 않는다.
-- 두 번째 값은 해제 0건의 원인을 가르는 데 쓴다. 0이면 이미 해제된 것이고, 0보다 크면 다른 주인이 쥐고 있다.

local bookingId = ARGV[1]
local departureStopOrder = tonumber(ARGV[2])
local arrivalStopOrder = tonumber(ARGV[3])
local bookedValue = "B:" .. bookingId

local released = 0
local foreign = 0

for i = 4, #ARGV do
    local separator = string.find(ARGV[i], ":", 1, true)
    local seatId = string.sub(ARGV[i], 1, separator - 1)
    local carIndex = tonumber(string.sub(ARGV[i], separator + 1))
    local carKey = KEYS[carIndex]

    for section = departureStopOrder, arrivalStopOrder - 1 do
        local field = seatId .. ":" .. section
        local value = redis.call("HGET", carKey, field)
        if value == bookedValue then
            redis.call("HDEL", carKey, field)
            released = released + 1
        elseif value then
            foreign = foreign + 1
        end
    end
end

return {released, foreign}
