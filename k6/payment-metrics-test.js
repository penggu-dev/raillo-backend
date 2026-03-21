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
//   1. 스케줄 설정 자동 생성 (서버가 떠있어야 함):
//      python3 test/db-scripts/generate_k6_schedule_config.py
//
//   2. k6 테스트 실행:
//      K6_WEB_DASHBOARD=true k6 run k6/payment-metrics-test.js
//
// schedule-config.json 없이도 실행 가능 (SCHEDULES 기본값 사용)
// ================================================================================

// --------------------------------------------------------------------------------
// 1. 설정 및 상수 정의
// --------------------------------------------------------------------------------
const VU = 30;  // 가상 유저 수 (동시 접속자)
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 계정 설정 (generate_members.py 기본값 기준)
// generate_members.py가 생성하는 멤버 번호: 202603030001, 202603030002, ...
const MEMBER_NO_START = 202603030001;
const MEMBER_PASSWORD = 'Test1234!';

// 스케줄 목록 — schedule-config.json에서 자동 로드하거나 수동으로 지정
// 자동 생성: python3 test/db-scripts/generate_k6_schedule_config.py
// → train search API 호출 → DB에서 좌석 범위 조회 → k6/schedule-config.json 생성
//
// 여러 스케줄에 분산하면 좌석 충돌이 줄어들고 더 많은 결제 메트릭을 수집할 수 있음
// 각 VU의 매 iteration마다 이 중 하나를 랜덤으로 선택하여 예약 시도
let SCHEDULES;
try {
    // generate_k6_schedule_config.py가 생성한 JSON 파일을 읽어옴
    // k6의 open()은 init 단계에서만 호출 가능 (setup/default 함수 밖)
    const configFile = open('./schedule-config.json');
    SCHEDULES = JSON.parse(configFile);
    console.log(`[Config] schedule-config.json 로드 완료: ${SCHEDULES.length}개 스케줄`);
} catch (e) {
    // JSON 파일이 없으면 기본값 사용 (수동 설정)
    console.warn('[Config] schedule-config.json 없음 → 기본 스케줄 사용');
    SCHEDULES = [
        { scheduleId: 10323, departureStation: 2, arrivalStation: 18, seatStart: 223775, seatEnd: 224187 },
    ];
}

// 실패 시나리오용 Toss 에러코드 풀
// paymentKey에 이 문자열을 포함시키면 WireMock이 해당 에러 응답을 반환함
// 배열에 같은 코드를 여러번 넣으면 그만큼 뽑힐 확률이 높아짐 (가중치 역할)
const ERROR_CODES = [
    'ERR_REJECT_CARD_PAYMENT',                   // 한도초과/잔액부족 (가중치 2배 — 가장 흔한 에러)
    'ERR_REJECT_CARD_PAYMENT',
    'ERR_INVALID_CARD_NUMBER',                   // 카드번호 오류
    'ERR_ALREADY_PROCESSED_PAYMENT',             // 중복 결제
    'ERR_REJECT_ACCOUNT_PAYMENT',                // 잔액부족 (계좌)
    'ERR_EXCEED_MAX_DAILY_PAYMENT_COUNT',        // 일일 결제 횟수 초과
    'ERR_FAILED_INTERNAL_SYSTEM_PROCESSING',     // 토스 내부 시스템 오류 (드묾)
    'ERR_NOT_FOUND_PAYMENT_SESSION',             // 결제 세션 만료 (드묾)
];

// k6 부하 패턴 설정
// stages: VU 수를 시간에 따라 조절
//   0~30초:    0 → 30 VU로 점진 증가 (ramp-up)
//   30초~2분:  30 VU 유지 (sustained load)
//   2분~2분30초: 30 → 0 VU로 점진 감소 (ramp-down)
// thresholds: 전체 요청의 95%가 5초 이내 응답해야 테스트 통과
export const options = {
    setupTimeout: '2m',  // setup() 함수 (로그인) 제한시간
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
// 2. Setup: 테스트 시작 전 각 VU가 사용할 JWT 토큰을 미리 발급
//    - k6의 setup()은 테스트 시작 전 1회만 실행됨
//    - 30명분의 로그인을 순차적으로 처리하여 accessToken 배열을 만듦
//    - 리턴값은 default 함수의 data 파라미터로 전달됨
// --------------------------------------------------------------------------------
export function setup() {
    const tokens = [];
    console.log(`[Setup] ${VU}명의 토큰 발급 시작 ...`);

    for (let i = 0; i < VU; i++) {
        // MEMBER_NO_START부터 순서대로 멤버 번호 생성
        // 예: 202603160001, 202603160002, ..., 202603160030
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
        sleep(0.01);  // 로그인 요청 간 약간의 간격
    }

    const validCount = tokens.filter(t => t !== null).length;
    console.log(`[Setup] 완료. ${validCount}/${VU}개 토큰 준비됨.`);
    console.log(`[Setup] 사용 스케줄 ${SCHEDULES.length}개 — 스케줄당 좌석 충돌 확률 감소`);
    return { tokens };
}

// --------------------------------------------------------------------------------
// 3. Main Test: 각 VU가 반복 실행하는 메인 함수
//    실제 결제 플로우를 시뮬레이션: 좌석 선택 → 결제 준비 → 결제 승인
//    - 매 iteration마다 SCHEDULES 중 하나를 랜덤 선택하여 요청 분산
//    - 80%는 정상 결제 (WireMock 성공 응답)
//    - 20%는 실패 결제 (WireMock이 Toss 에러 응답 반환)
// --------------------------------------------------------------------------------
export default function (data) {
    // __VU: k6 내장 변수, 현재 VU 번호 (1부터 시작)
    // 각 VU가 setup()에서 발급받은 자기 토큰을 가져옴
    const vuIndex = (__VU - 1) % data.tokens.length;
    const tokenData = data.tokens[vuIndex];

    // 토큰 발급 실패한 VU는 스킵
    if (!tokenData) { sleep(1); return; }

    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${tokenData.token}`,
    };

    // 매 iteration마다 스케줄을 랜덤으로 선택하여 요청 분산
    // → 하나의 스케줄에 좌석이 몰리지 않아 충돌 감소
    const schedule = SCHEDULES[Math.floor(Math.random() * SCHEDULES.length)];

    // ── Step 1: Pending Booking 생성 ──
    // 선택된 스케줄의 좌석 범위 내에서 랜덤으로 1~2석을 골라 임시 예약 요청
    // 성공하면 pendingBookingId를 받아서 다음 단계로 진행
    const seatCount = randomIntBetween(1, 2);
    const seatIds = pickRandomSeats(seatCount, schedule.seatStart, schedule.seatEnd);
    const passengerTypes = seatIds.map(() => 'ADULT');  // 좌석 수만큼 ADULT 탑승자

    const pendingRes = http.post(`${BASE_URL}/api/v1/pending-bookings`, JSON.stringify({
        trainScheduleId: schedule.scheduleId,
        departureStationId: schedule.departureStation,
        arrivalStationId: schedule.arrivalStation,
        passengerTypes: passengerTypes,
        seatIds: seatIds,
    }), { headers, tags: { name: 'pending_booking' }, timeout: '10s' });

    // 좌석 충돌(409) 등으로 실패하면 이번 iteration은 포기하고 다음으로
    if (pendingRes.status !== 200 && pendingRes.status !== 201) {
        sleep(randomIntBetween(1, 2));
        return;
    }

    // 응답에서 pendingBookingId 추출 (결제 준비에 필요)
    let pendingBookingId;
    try {
        pendingBookingId = JSON.parse(pendingRes.body).result.pendingBookingId;
    } catch (e) {
        console.error(`[Pending] 응답 파싱 실패: ${pendingRes.body}`);
        sleep(1);
        return;
    }

    // ── Step 2: Payment Prepare ──
    // pendingBookingId를 넘겨서 Order + Payment 엔티티 생성
    // 응답으로 orderId와 amount를 받음 (원래는 이걸로 Toss 위젯을 띄우는 단계)
    const prepareRes = http.post(`${BASE_URL}/api/v1/payments/prepare`, JSON.stringify({
        pendingBookingIds: [pendingBookingId],
    }), { headers, tags: { name: 'payment_prepare' }, timeout: '10s' });

    // → 이 시점에서 payment_prepare_total 메트릭이 증가함
    check(prepareRes, {
        'prepare 성공': (r) => r.status === 200,
    });

    if (prepareRes.status !== 200) {
        console.error(`[Prepare] 실패(${prepareRes.status}): ${prepareRes.body}`);
        sleep(1);
        return;
    }

    // orderId, amount 추출 (결제 승인에 필요)
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
    // 원래 플로우: 프론트 → Toss SDK → paymentKey 발급 → 백엔드 confirm
    // 여기서는 WireMock이 Toss 역할을 하므로 paymentKey를 직접 생성
    const isFailure = Math.random() < 0.2;  // 20% 확률로 실패 시나리오
    let paymentKey;

    if (isFailure) {
        // 실패 케이스: paymentKey에 에러코드를 포함시킴
        // 예: "ERR_REJECT_CARD_PAYMENT_1710567890_3"
        // → WireMock이 paymentKey에서 "ERR_REJECT_CARD_PAYMENT"를 감지하고 403 에러 반환
        // → payment_confirm_failure_total{reason=toss_error, error_code=REJECT_CARD_PAYMENT} 증가
        const errorCode = ERROR_CODES[Math.floor(Math.random() * ERROR_CODES.length)];
        paymentKey = `${errorCode}_${Date.now()}_${__VU}`;
    } else {
        // 성공 케이스: 일반 paymentKey
        // 예: "pay_test_1710567890_3_5"
        // → WireMock이 에러코드 패턴을 못 찾고 기본 성공 응답 반환
        // → payment_confirm_success_total 증가, toss_api_duration_seconds 기록
        paymentKey = `pay_test_${Date.now()}_${__VU}_${__ITER}`;
    }

    const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`, JSON.stringify({
        paymentKey: paymentKey,
        orderId: orderId,
        amount: amount,
    }), { headers, tags: { name: 'payment_confirm' }, timeout: '10s' });

    // 의도한 시나리오대로 응답이 왔는지 검증
    if (isFailure) {
        check(confirmRes, {
            'confirm 실패 응답 수신': (r) => r.status >= 400,
        });
    } else {
        check(confirmRes, {
            'confirm 성공': (r) => r.status === 200,
        });
    }

    // 실제 사용자처럼 1~3초 간격을 두고 다음 iteration 시작
    sleep(randomIntBetween(1, 3));
}

// --------------------------------------------------------------------------------
// 4. Helper Functions
// --------------------------------------------------------------------------------

// min 이상 max 이하의 랜덤 정수 반환
function randomIntBetween(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// 지정된 좌석 범위(seatStart ~ seatEnd) 내에서 count개의 좌석 ID를 뽑아 반환
// - 2석 이상일 때 70% 확률로 연속 좌석 (실제 예매처럼 나란히 앉는 패턴)
//   예: [223800, 223801]
// - 나머지는 범위 내 랜덤 좌석 (중복 없이)
//   예: [223812, 224055]
// - 1석이면 항상 랜덤으로 1개만 뽑음
function pickRandomSeats(count, seatStart, seatEnd) {
    const seats = [];

    // 2석 이상 + 70% 확률 → 연속 좌석 시도
    if (count > 1 && Math.random() < 0.7) {
        // 시작점을 범위 내에서 랜덤 선택 (끝에서 count만큼 여유를 둠)
        const start = randomIntBetween(seatStart, seatEnd - count + 1);
        for (let i = 0; i < count; i++) {
            seats.push(start + i);  // 시작점부터 연속으로 추가
        }
        return seats;
    }

    // 랜덤 좌석: 중복 없이 count개를 뽑을 때까지 반복
    while (seats.length < count) {
        const seatId = randomIntBetween(seatStart, seatEnd);
        if (seats.indexOf(seatId) === -1) {  // 중복 체크
            seats.push(seatId);
        }
    }
    return seats.sort((a, b) => a - b);  // 오름차순 정렬
}
