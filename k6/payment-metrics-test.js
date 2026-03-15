import http from 'k6/http';
import { check, sleep } from 'k6';

// ================================================================================
// Payment 메트릭 테스트 스크립트
// Grafana에서 Payment 관련 메트릭을 시각적으로 확인하기 위한 소규모 부하 테스트
//
// 사전 준비:
//   1. docker compose -f compose-test.yaml up -d
//   2. python test/db-scripts/generate_members.py --total 100 --batch 100
//   3. DB에 train_schedule, train_car, seat 데이터 존재 확인
//   4. (선택) python test/db-scripts/generate_schedule_preoccupy.py --schedule-ids <ID>
//
// 실행:
//   k6 run k6/payment-metrics-test.js
//
// 환경변수로 커스텀 가능:
//   k6 run -e BASE_URL=http://localhost:8080 \
//          -e SCHEDULE_ID=10813 \
//          -e DEPARTURE_STATION=2 \
//          -e ARRIVAL_STATION=18 \
//          -e SEAT_START=46372 \
//          -e SEAT_END=47179 \
//          k6/payment-metrics-test.js
// ================================================================================

// --------------------------------------------------------------------------------
// 1. 설정 및 상수 정의
// --------------------------------------------------------------------------------
const VU = 30;
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 계정 설정 (generate_members.py 기본값 기준)
const MEMBER_NO_START = 202603030001;
const MEMBER_PASSWORD = 'Test1234!';

// 스케줄/역/좌석 설정 — DB 데이터에 맞게 환경변수로 조정
const TRAIN_SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1');
const DEPARTURE_STATION_ID = parseInt(__ENV.DEPARTURE_STATION || '2');
const ARRIVAL_STATION_ID = parseInt(__ENV.ARRIVAL_STATION || '11');
const SEAT_ID_START = parseInt(__ENV.SEAT_START || '1');
const SEAT_ID_END = parseInt(__ENV.SEAT_END || '100');

// 실패 시나리오 에러코드 풀 (가중치 포함)
const ERROR_CODES = [
    'ERR_REJECT_CARD_PAYMENT',
    'ERR_REJECT_CARD_PAYMENT',                  // 빈도 높음: 한도초과/잔액부족
    'ERR_INVALID_CARD_NUMBER',                   // 카드번호 오류
    'ERR_ALREADY_PROCESSED_PAYMENT',             // 중복 결제
    'ERR_REJECT_ACCOUNT_PAYMENT',                // 잔액부족 (계좌)
    'ERR_EXCEED_MAX_DAILY_PAYMENT_COUNT',        // 일일 횟수 초과
    'ERR_FAILED_INTERNAL_SYSTEM_PROCESSING',     // 토스 내부 오류 (드묾)
    'ERR_NOT_FOUND_PAYMENT_SESSION',             // 세션 만료 (드묾)
];

export const options = {
    setupTimeout: '2m',
    stages: [
        { duration: '30s', target: VU },
        { duration: '1m30s', target: VU },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<5000'],
    },
};

// --------------------------------------------------------------------------------
// 2. Setup: 각 VU용 토큰 발급
// --------------------------------------------------------------------------------
export function setup() {
    const tokens = [];
    console.log(`[Setup] ${VU}명의 토큰 발급 시작 ...`);

    for (let i = 0; i < VU; i++) {
        const memberNo = (MEMBER_NO_START + i).toString();
        const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
            memberNo: memberNo,
            password: MEMBER_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json' } });

        if (res.status === 200) {
            try {
                const body = JSON.parse(res.body);
                tokens.push({ token: body.result.accessToken, memberNo: memberNo });
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

    const validCount = tokens.filter(t => t !== null).length;
    console.log(`[Setup] 완료. ${validCount}/${VU}개 토큰 준비됨.`);
    return { tokens };
}

// --------------------------------------------------------------------------------
// 3. Main Test: pending booking → prepare → confirm
// --------------------------------------------------------------------------------
export default function (data) {
    const vuIndex = (__VU - 1) % data.tokens.length;
    const tokenData = data.tokens[vuIndex];

    if (!tokenData) { sleep(1); return; }

    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${tokenData.token}`,
    };

    // ── Step 1: Pending Booking 생성 ──
    const seatCount = randomIntBetween(1, 2);
    const seatIds = pickRandomSeats(seatCount);
    const passengerTypes = seatIds.map(() => 'ADULT');

    const pendingRes = http.post(`${BASE_URL}/api/v1/pending-bookings`, JSON.stringify({
        trainScheduleId: TRAIN_SCHEDULE_ID,
        departureStationId: DEPARTURE_STATION_ID,
        arrivalStationId: ARRIVAL_STATION_ID,
        passengerTypes: passengerTypes,
        seatIds: seatIds,
    }), { headers, tags: { name: 'pending_booking' }, timeout: '10s' });

    if (pendingRes.status !== 200 && pendingRes.status !== 201) {
        // 좌석 충돌(409) 등 → 다음 iteration
        sleep(randomIntBetween(1, 2));
        return;
    }

    let pendingBookingId;
    try {
        pendingBookingId = JSON.parse(pendingRes.body).result.pendingBookingId;
    } catch (e) {
        console.error(`[Pending] 응답 파싱 실패: ${pendingRes.body}`);
        sleep(1);
        return;
    }

    // ── Step 2: Payment Prepare ──
    const prepareRes = http.post(`${BASE_URL}/api/v1/payments/prepare`, JSON.stringify({
        pendingBookingIds: [pendingBookingId],
    }), { headers, tags: { name: 'payment_prepare' }, timeout: '10s' });

    check(prepareRes, {
        'prepare 성공': (r) => r.status === 200,
    });

    if (prepareRes.status !== 200) {
        console.error(`[Prepare] 실패(${prepareRes.status}): ${prepareRes.body}`);
        sleep(1);
        return;
    }

    let orderId, amount;
    try {
        const prepareBody = JSON.parse(prepareRes.body).result;
        orderId = prepareBody.orderId;
        amount = prepareBody.amount;
    } catch (e) {
        console.error(`[Prepare] 응답 파싱 실패: ${prepareRes.body}`);
        sleep(1);
        return;
    }

    // ── Step 3: Payment Confirm (80% 성공 / 20% 실패) ──
    const isFailure = Math.random() < 0.2;
    let paymentKey;

    if (isFailure) {
        const errorCode = ERROR_CODES[Math.floor(Math.random() * ERROR_CODES.length)];
        paymentKey = `${errorCode}_${Date.now()}_${__VU}`;
    } else {
        paymentKey = `pay_test_${Date.now()}_${__VU}_${__ITER}`;
    }

    const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`, JSON.stringify({
        paymentKey: paymentKey,
        orderId: orderId,
        amount: amount,
    }), { headers, tags: { name: 'payment_confirm' }, timeout: '10s' });

    if (isFailure) {
        check(confirmRes, {
            'confirm 실패 응답 수신': (r) => r.status >= 400,
        });
    } else {
        check(confirmRes, {
            'confirm 성공': (r) => r.status === 200,
        });
    }

    sleep(randomIntBetween(1, 3));
}

// --------------------------------------------------------------------------------
// 4. Helper Functions
// --------------------------------------------------------------------------------
function randomIntBetween(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function pickRandomSeats(count) {
    const seats = [];
    // 연속 좌석 우선 시도
    if (count > 1 && Math.random() < 0.7) {
        const start = randomIntBetween(SEAT_ID_START, SEAT_ID_END - count + 1);
        for (let i = 0; i < count; i++) {
            seats.push(start + i);
        }
        return seats;
    }
    // 랜덤 좌석
    while (seats.length < count) {
        const seatId = randomIntBetween(SEAT_ID_START, SEAT_ID_END);
        if (seats.indexOf(seatId) === -1) {
            seats.push(seatId);
        }
    }
    return seats.sort((a, b) => a - b);
}
