import http from 'k6/http';
import { sleep } from 'k6';

// --------------------------------------------------------------------------------
// 1. 설정 및 상수 정의
// --------------------------------------------------------------------------------
let VU = 100 ;

export const options = {
    setupTimeout: '5m',
    stages: [
        { duration: '1m', target: VU },
        { duration: '3m', target: VU },
        { duration: '1m', target: 0 }
    ],
    thresholds: {
        http_req_duration: ['p(95)<5000'],
    },
};

// API 및 계정 설정
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const LOGIN_URL = `${BASE_URL}/auth/login`;
const RESERVATION_URL = `${BASE_URL}/api/v1/pending-bookings`;
const MEMBER_NO_START = 202603030001;
const COMMON_PASSWORD = 'Test1234!';
const SINGLE_PASSENGER_TYPE = 'ADULT';

// 예약 데이터
let SCHEDULES;
try {
    const configFile = open('./schedule-config.json');
    const parsedSchedules = JSON.parse(configFile);
    SCHEDULES = parsedSchedules.filter(isValidSchedule);

    if (SCHEDULES.length === 0) {
        throw new Error('유효한 예약 데이터가 없습니다.');
    }
} catch (e) {
    throw new Error(`[Config] schedule-config.json 로드 실패: ${e.message}`);
}

// --------------------------------------------------------------------------------
// 2. Setup (직렬 로그인)
// --------------------------------------------------------------------------------
export function setup() {
    const tokens = [];
    console.log(`[Setup] ${VU}명의 토큰 발급 시작 ...`);

    for (let i = 0; i < VU; i++) {
        const memberNo = (MEMBER_NO_START + i).toString();
        const payload = JSON.stringify({ memberNo: memberNo, password: COMMON_PASSWORD });
        const params = { headers: { 'Content-Type': 'application/json' } };

        const res = http.post(LOGIN_URL, payload, params);

        if (res.status === 200) {
            try {
                tokens.push(JSON.parse(res.body).result.accessToken);
            } catch (e) {
                console.error(`[Setup] 파싱 에러: ${memberNo}`);
                tokens.push(null);
            }
        } else {
            console.error(`[Setup] 로그인 실패(${res.status}): ${memberNo}`);
            tokens.push(null);
        }
        sleep(0.01);
    }
    console.log(`[Setup] 완료. 총 ${tokens.length}개 토큰 준비됨.`);
    console.log(`[Setup] 예약 스케줄 ${SCHEDULES.length}개 사용.`);
    return tokens;
}


// --------------------------------------------------------------------------------
// 3. Main Test
// --------------------------------------------------------------------------------
export default function (tokens) {
    const userIndex = (__VU - 1) % VU;
    const token = tokens[userIndex];
    const memberNo = (MEMBER_NO_START + userIndex).toString();

    if (!token) { sleep(1); return; }

    const schedule = getRandomSchedule();
    const seatIds = generateSingleSeatId(schedule);

    if (seatIds.length !== 1) {
        sleep(0.2);
        return;
    }

    const requestData = {
        trainScheduleId: schedule.scheduleId,
        departureStationId: schedule.departureStation,
        arrivalStationId: schedule.arrivalStation,
        passengerTypes: [SINGLE_PASSENGER_TYPE],
        seatIds: seatIds
    };

    const response = http.post(RESERVATION_URL, JSON.stringify(requestData), {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + token,
        },
        timeout: '10s',
    });

    if (response.status !== 200 && response.status !== 201 && response.status !== 409) {
        console.error(`🚨 에러! [${memberNo}] Status: ${response.status}, Body: ${response.body}`);
    }
}

// --------------------------------------------------------------------------------
// 4. Helper Functions
// --------------------------------------------------------------------------------
function isValidSchedule(schedule) {
    return schedule &&
        Number.isInteger(schedule.scheduleId) &&
        Number.isInteger(schedule.departureStation) &&
        Number.isInteger(schedule.arrivalStation) &&
        Number.isInteger(schedule.seatStart) &&
        Number.isInteger(schedule.seatEnd) &&
        schedule.seatStart <= schedule.seatEnd;
}

function getRandomSchedule() {
    return SCHEDULES[Math.floor(Math.random() * SCHEDULES.length)];
}

function generateSingleSeatId(schedule) {
    const totalRange = schedule.seatEnd - schedule.seatStart + 1;
    if (totalRange < 1) {
        return [];
    }

    const seatId = Math.floor(Math.random() * totalRange) + schedule.seatStart;
    return [seatId];
}
