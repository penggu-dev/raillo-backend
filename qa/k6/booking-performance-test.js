import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BRANCH = __ENV.BRANCH || 'develop';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CONFIG_PATH = __ENV.CONFIG || 'config/booking-performance-config.json';
const SCENARIO = __ENV.SCENARIO || 'high-contention';
const VUS = Number(__ENV.VUS || 100);
const RAMP_UP = __ENV.RAMP_UP || '30s';
const DURATION = __ENV.DURATION || '2m';
const RAMP_DOWN = __ENV.RAMP_DOWN || '30s';
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0);

const bookingSuccess = new Counter('booking_success');
const bookingConflict = new Counter('booking_conflict');
const bookingSystemError = new Counter('booking_system_error');
const loginFailure = new Counter('login_failure');

const CONFIG = JSON.parse(openConfig(CONFIG_PATH));

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  stages: [
    { duration: RAMP_UP, target: VUS },
    { duration: DURATION, target: VUS },
    { duration: RAMP_DOWN, target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<5000'],
    booking_system_error: ['count<1'],
  },
};

function openConfig(path) {
  try {
    return open(path);
  } catch (error) {
    if (path.startsWith('qa/k6/')) {
      return open(path.replace(/^qa\/k6\//, ''));
    }
    throw error;
  }
}

export function setup() {
  validateConfig(CONFIG);

  const tokens = [];
  const memberCount = Math.min(VUS, CONFIG.members.length);
  console.log(`[Setup] branch=${BRANCH}, scenario=${SCENARIO}, members=${memberCount}, baseUrl=${BASE_URL}`);

  for (let i = 0; i < memberCount; i++) {
    const memberNo = CONFIG.members[i];
    const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
      memberNo,
      password: CONFIG.memberPassword,
    }), {
      headers: { 'Content-Type': 'application/json' },
      timeout: REQUEST_TIMEOUT,
      tags: { name: 'login' },
    });

    if (res.status !== 200) {
      loginFailure.add(1);
      console.error(`[Setup] login failed memberNo=${memberNo}, status=${res.status}, body=${res.body}`);
      tokens.push(null);
      continue;
    }

    try {
      const accessToken = JSON.parse(res.body).result.accessToken;
      tokens.push({ memberNo, accessToken });
    } catch (error) {
      loginFailure.add(1);
      console.error(`[Setup] token parse failed memberNo=${memberNo}, body=${res.body}`);
      tokens.push(null);
    }
  }

  const validTokens = tokens.filter((token) => token !== null).length;
  console.log(`[Setup] validTokens=${validTokens}/${memberCount}`);
  return { tokens };
}

export default function (data) {
  const tokenData = data.tokens[(__VU - 1) % data.tokens.length];
  if (!tokenData) {
    sleep(1);
    return;
  }

  const schedule = CONFIG.schedules[(__VU + __ITER) % CONFIG.schedules.length];
  const sectionMode = chooseSectionMode();
  const seatPool = chooseSeatPool(schedule);
  if (seatPool.length === 0) {
    bookingSystemError.add(1);
    console.error(`[Config] empty seat pool scheduleId=${schedule.scheduleId}`);
    return;
  }

  const seatId = seatPool[(__VU + __ITER) % seatPool.length];
  const payload = BRANCH === 'v1'
    ? buildV1Payload(schedule, seatId, sectionMode)
    : buildDevelopPayload(schedule, seatId, sectionMode);

  const res = http.post(endpointFor(BRANCH), JSON.stringify(payload), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${tokenData.accessToken}`,
    },
    timeout: REQUEST_TIMEOUT,
    tags: { name: 'booking_create', branch: BRANCH, scenario: SCENARIO },
  });

  const result = classifyResponse(res);
  if (result === 'success') {
    bookingSuccess.add(1);
  } else if (result === 'conflict') {
    bookingConflict.add(1);
  } else {
    bookingSystemError.add(1);
    console.error(`[Booking] system_error branch=${BRANCH}, status=${res.status}, body=${res.body}`);
  }

  check(res, {
    'booking success or domain conflict': (r) => classifyResponse(r) !== 'system_error',
  });

  if (THINK_TIME_SECONDS > 0) {
    sleep(THINK_TIME_SECONDS);
  }
}

function validateConfig(config) {
  if (!Array.isArray(config.members) || config.members.length === 0) {
    throw new Error('CONFIG.members must not be empty');
  }
  if (!Array.isArray(config.schedules) || config.schedules.length === 0) {
    throw new Error('CONFIG.schedules must not be empty');
  }
  for (const schedule of config.schedules) {
    const required = [
      'scheduleId',
      'departureStationId',
      'midStationId',
      'arrivalStationId',
      'occupiedSeatIds',
      'openSeatIds',
    ];
    for (const key of required) {
      if (!(key in schedule)) {
        throw new Error(`schedule missing required key: ${key}`);
      }
    }
  }
}

function endpointFor(branch) {
  if (branch === 'v1') {
    return `${BASE_URL}/api/v1/booking/reservation`;
  }
  return `${BASE_URL}/api/v1/pending-bookings`;
}

function buildV1Payload(schedule, seatId, sectionMode) {
  const stations = stationsFor(schedule, sectionMode);
  return {
    trainScheduleId: schedule.scheduleId,
    departureStationId: stations.departureStationId,
    arrivalStationId: stations.arrivalStationId,
    passengers: [{ passengerType: 'ADULT', count: 1 }],
    seatIds: [seatId],
    tripType: 'OW',
  };
}

function buildDevelopPayload(schedule, seatId, sectionMode) {
  const stations = stationsFor(schedule, sectionMode);
  return {
    trainScheduleId: schedule.scheduleId,
    departureStationId: stations.departureStationId,
    arrivalStationId: stations.arrivalStationId,
    passengerTypes: ['ADULT'],
    seatIds: [seatId],
  };
}

function stationsFor(schedule, sectionMode) {
  if (sectionMode === 'first_half') {
    return {
      departureStationId: schedule.departureStationId,
      arrivalStationId: schedule.midStationId,
    };
  }
  if (sectionMode === 'second_half') {
    return {
      departureStationId: schedule.midStationId,
      arrivalStationId: schedule.arrivalStationId,
    };
  }
  return {
    departureStationId: schedule.departureStationId,
    arrivalStationId: schedule.arrivalStationId,
  };
}

function chooseSectionMode() {
  if (SCENARIO === 'section-aware') {
    return __ITER % 2 === 0 ? 'first_half' : 'second_half';
  }
  return 'full';
}

function chooseSeatPool(schedule) {
  if (SCENARIO === 'sold-conflict') {
    return schedule.occupiedSeatIds;
  }
  if (SCENARIO === 'open-only') {
    return schedule.openSeatIds;
  }
  return __ITER % 3 === 0 ? schedule.occupiedSeatIds : schedule.openSeatIds;
}

function classifyResponse(res) {
  if (res.status === 200 || res.status === 201) {
    return 'success';
  }
  if (res.status === 409) {
    return 'conflict';
  }
  return 'system_error';
}
