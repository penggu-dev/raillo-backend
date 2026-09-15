-- get_hold_seats_count.lua
-- CarType별 Seat Hold 점유 좌석 수 계산 Lua 스크립트
--
-- KEYS[1..N]: TrainCar Hold Index 키들 (예: {schedule:1785}:traincar:231:holding-seats)
-- ARGV[1..M]: 검색 구간 sections (예: "0-1", "1-2", "2-3")
--
-- 반환값: Seat Hold 점유 좌석 수 (중복 제거된)

-- Redis 서버 시각 사용 (Java 서버와 clock skew 방지)
local currentTime = tonumber(redis.call("TIME")[1])

-- 검색 구간 Set 생성
local searchSections = {}
for i = 1, #ARGV do
    searchSections[ARGV[i]] = true
end

-- seatId 중복 제거용 Set
local uniqueSeats = {}

-- 각 TrainCar Hold Index 키에 대해 조회
for _, key in ipairs(KEYS) do
    -- 만료되지 않은 TrainCar Hold Index 멤버 조회
    local members = redis.call("ZRANGEBYSCORE", key, currentTime, "+inf")

    for _, member in ipairs(members) do
        -- 멤버 파싱: "42:0-1" -> seatId="42", section="0-1"
        local seatId, section = member:match("^(%d+):(.+)$")

        -- 검색 구간과 겹치는지 확인
        if seatId and searchSections[section] then
            uniqueSeats[seatId] = true
        end
    end
end

-- 중복 제거된 좌석 수 반환
local count = 0
for _ in pairs(uniqueSeats) do
    count = count + 1
end

return count
