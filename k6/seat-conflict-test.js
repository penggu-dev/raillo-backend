import http from 'k6/http';
import { sleep } from 'k6';

// --------------------------------------------------------------------------------
// 1. 설정 및 상수 정의
// --------------------------------------------------------------------------------
let VU = 300 ;

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
const LOGIN_URL = 'http://localhost:8080/auth/login';
const RESERVATION_URL = 'http://localhost:8080/api/v1/pending-bookings';
const MEMBER_NO_START = 202601200001;
const COMMON_PASSWORD = 'testpassword';

// 예약 데이터
const TRAIN_SCHEDULE_ID = 10813;
const STATIONS = [2, 7, 8, 9, 11, 13, 18];
const SEAT_RANGES = [ { min: 46372, max: 47179 } ];
const PASSENGER_COMBINATIONS = [
    [{ "passengerType": "ADULT", "count": 1 }],
    [{ "passengerType": "ADULT", "count": 2 }],
    [{ "passengerType": "ADULT", "count": 3 }],
    [{ "passengerType": "ADULT", "count": 1 }, { "passengerType": "CHILD", "count": 1 }],
    [{ "passengerType": "ADULT", "count": 2 }, { "passengerType": "CHILD", "count": 1 }],
    [{ "passengerType": "ADULT", "count": 1 }, { "passengerType": "CHILD", "count": 2 }]
];


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

    const stations = getRandomStationPair();
    const passengers = PASSENGER_COMBINATIONS[Math.floor(Math.random() * PASSENGER_COMBINATIONS.length)];
    const passengerCount = getTotalPassengerCount(passengers);
    const seatIds = generateRandomSeats(passengerCount);

    const requestData = {
        trainScheduleId: TRAIN_SCHEDULE_ID,
        departureStationId: stations.departureStationId,
        arrivalStationId: stations.arrivalStationId,
        passengerTypes: getFlatPassengerTypes(passengers),
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
function getRandomStationPair() {
    const departureIndex = Math.floor(Math.random() * (STATIONS.length - 1));
    const arrivalIndex = departureIndex + 1 + Math.floor(Math.random() * (STATIONS.length - departureIndex - 1));
    return { departureStationId: STATIONS[departureIndex], arrivalStationId: STATIONS[arrivalIndex] };
}

function getTotalPassengerCount(passengers) {
    let total = 0;
    for (let i = 0; i < passengers.length; i++) { total += passengers[i].count; }
    return total;
}

function getFlatPassengerTypes(passengers) {
    const flatList = [];
    for (let i = 0; i < passengers.length; i++) {
        const p = passengers[i];
        for (let c = 0; c < p.count; c++) { flatList.push(p.passengerType); }
    }
    return flatList;
}

function generateRandomSeats(passengerCount) {
    const seats = [];
    const maxAttempts = 50;
    let attempts = 0;
    if (Math.random() < 0.6 && passengerCount > 1) {
        const rangeIndex = Math.floor(Math.random() * SEAT_RANGES.length);
        const range = SEAT_RANGES[rangeIndex];
        const maxStartSeat = range.max - passengerCount + 1;
        if (maxStartSeat >= range.min) {
            const startSeat = Math.floor(Math.random() * (maxStartSeat - range.min + 1)) + range.min;
            for (let i = 0; i < passengerCount; i++) { seats.push(startSeat + i); }
            return seats;
        }
    }
    while (seats.length < passengerCount && attempts < maxAttempts) {
        const rangeIndex = Math.floor(Math.random() * SEAT_RANGES.length);
        const range = SEAT_RANGES[rangeIndex];
        const seatId = Math.floor(Math.random() * (range.max - range.min + 1)) + range.min;
        if (seats.indexOf(seatId) === -1) { seats.push(seatId); }
        attempts++;
    }
    return seats.sort((a, b) => a - b);
}
