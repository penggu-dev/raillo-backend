-- seat_release.lua
-- 좌석 임시 점유 해제 Lua 스크립트
--
-- KEYS[1]: hold key (예: {seat:1001:12}:hold:pending_abc123)
--
-- 반환값:
-- 성공: {1, "RELEASE_SUCCESS"}
-- 키 없음: {1, "ALREADY_RELEASED"} (이미 만료되었거나 해제됨 - 성공으로 처리)

local holdKey = KEYS[1]

-- Hold 키 삭제 (없어도 에러 아님)
local deleted = redis.call("DEL", holdKey)

if deleted == 1 then
    return {1, "RELEASE_SUCCESS"}
else
    return {1, "ALREADY_RELEASED"}
end
