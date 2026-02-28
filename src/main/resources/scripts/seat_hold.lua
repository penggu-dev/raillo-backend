-- seat_hold.lua
-- 좌석 임시 점유 Lua 스크립트
--
-- KEYS[1]: hold key      (예: {schedule:1001}:seat:12:hold:pending_abc123)
-- KEYS[2]: holds key     (예: {schedule:1001}:seat:12:holds) - Hold 목록 인덱스
-- KEYS[3]: trainCarHoldIndexKey  (예: {schedule:1785}:traincar:231:holding-seats) - Hold Index
--
-- ARGV[1]: ttl (seconds)
-- ARGV[2]: pendingBookingId (holds Set에 추가할 값)
-- ARGV[3]: seatId
-- ARGV[4...]: sections ("0-1", "1-2", ...)
--
-- 반환값:
-- 성공: {1, "HOLD_SUCCESS"}
-- 실패: {0, "CONFLICT_WITH_HOLD", "충돌구간"}

local holdKey = KEYS[1]
local seatHoldIndexKey = KEYS[2]
local trainCarHoldIndexKey = KEYS[3]
local ttl = tonumber(ARGV[1])
local pendingBookingId = ARGV[2]
local seatId = ARGV[3]

-- 요청 구간 수집
local sections = {}
for i = 4, #ARGV do
    table.insert(sections, ARGV[i])
end

-- 1. HOLD 충돌 검사 (다른 사용자의 임시 점유와 겹치는지)
-- holds Set에서 현재 Hold 목록 조회 (KEYS 명령 대신 SMEMBERS 사용)
local holdIds = redis.call("SMEMBERS", seatHoldIndexKey)

for _, holdId in ipairs(holdIds) do
    -- 자기 자신은 제외 (재시도 케이스 대응)
    if holdId ~= pendingBookingId then
        -- string.gsub은 패턴 매칭을 사용하므로 특수문자('-' 등)가 포함된 ID 처리 시 오류 발생 가능
        -- 따라서 문자열 길이를 기반으로 prefix를 추출하여 단순 결합 방식으로 변경
        local prefixLen = string.len(holdKey) - string.len(pendingBookingId)
        local prefix = string.sub(holdKey, 1, prefixLen)
        local otherHoldKey = prefix .. holdId

        -- 만료된 Hold 키 정리 (Lazy Cleanup)
        if redis.call("EXISTS", otherHoldKey) == 0 then
            redis.call("SREM", seatHoldIndexKey, holdId)
        else
            -- 유효한 Hold 키인 경우 충돌 검사
            for _, s in ipairs(sections) do
                if redis.call("SISMEMBER", otherHoldKey, s) == 1 then
                    return {0, "CONFLICT_WITH_HOLD", s}
                end
            end
        end
    end
end

-- 2. 임시 점유 생성
for _, s in ipairs(sections) do
    redis.call("SADD", holdKey, s)
end
redis.call("EXPIRE", holdKey, ttl)

-- 3. holds 인덱스에 추가 (TTL 동일하게 설정)
redis.call("SADD", seatHoldIndexKey, pendingBookingId)
redis.call("EXPIRE", seatHoldIndexKey, ttl)

-- 4. Hold Index에 compound 멤버 등록
local currentTime = redis.call("TIME")[1]
local expiryTime = currentTime + ttl

for _, s in ipairs(sections) do
    local member = seatId .. ":" .. s  -- "42:0-1" 형태
    redis.call("ZADD", trainCarHoldIndexKey, expiryTime, member)
end
redis.call("EXPIRE", trainCarHoldIndexKey, ttl * 2)

return {1, "HOLD_SUCCESS"}
