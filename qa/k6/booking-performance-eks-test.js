import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BRANCH = __ENV.BRANCH || 'develop';
const BASE_URL = __ENV.BASE_URL || 'http://raillo-v2.v2.svc.cluster.local';
const CONFIG_PATH = __ENV.CONFIG || '/scripts/booking-performance-config.json';
const SUMMARY_PATH = __ENV.SUMMARY_PATH || `booking-performance-${BRANCH}-summary.json`;
const SCENARIO = __ENV.SCENARIO || 'high-contention';
const VUS = Number(__ENV.VUS || 100);
const RAMP_UP = __ENV.RAMP_UP || '10s';
const DURATION = __ENV.DURATION || '40s';
const RAMP_DOWN = __ENV.RAMP_DOWN || '10s';
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '10s';
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0);

const bookingSuccess = new Counter('booking_success');
const bookingConflict = new Counter('booking_conflict');
const bookingSystemError = new Counter('booking_system_error');
const loginFailure = new Counter('login_failure');
const bookingDuration = new Trend('booking_duration', true);

const CONFIG = JSON.parse(openConfig(CONFIG_PATH));

export const options = {
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  stages: [
    { duration: RAMP_UP, target: VUS },
    { duration: DURATION, target: VUS },
    { duration: RAMP_DOWN, target: 0 },
  ],
};

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
      tags: { name: 'login', branch: BRANCH },
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

  bookingDuration.add(res.timings.duration, {
    branch: BRANCH,
    scenario: SCENARIO,
    result: classifyResponse(res),
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

export function handleSummary(data) {
  const safeSummary = {
    metadata: {
      branch: BRANCH,
      baseUrl: BASE_URL,
      scenario: SCENARIO,
      vus: VUS,
      rampUp: RAMP_UP,
      duration: DURATION,
      rampDown: RAMP_DOWN,
      config: CONFIG_PATH,
    },
    metrics: data.metrics,
    root_group: data.root_group,
  };

  return {
    stdout: buildConsoleSummary(data),
    [SUMMARY_PATH]: JSON.stringify(safeSummary, null, 2),
  };
}

function buildConsoleSummary(data) {
  const iterations = metricValue(data, 'iterations', 'rate');
  const avg = metricValue(data, 'booking_duration', 'avg');
  const p90 = metricValue(data, 'booking_duration', 'p(90)');
  const p95 = metricValue(data, 'booking_duration', 'p(95)');
  const p99 = metricValue(data, 'booking_duration', 'p(99)');
  const success = metricValue(data, 'booking_success', 'count');
  const conflict = metricValue(data, 'booking_conflict', 'count');
  const systemError = metricValue(data, 'booking_system_error', 'count');

  return [
    '',
    'Booking API EKS summary',
    `branch=${BRANCH}`,
    `iterations/sec=${iterations.toFixed(2)}`,
    `booking_avg_ms=${avg.toFixed(2)}`,
    `booking_p90_ms=${p90.toFixed(2)}`,
    `booking_p95_ms=${p95.toFixed(2)}`,
    `booking_p99_ms=${p99.toFixed(2)}`,
    `success=${success}`,
    `conflict=${conflict}`,
    `system_error=${systemError}`,
    '',
  ].join('\n');
}

function metricValue(data, metricName, valueName) {
  const metric = data.metrics[metricName] || {};
  if (valueName in metric) {
    return Number(metric[valueName] || 0);
  }
  const values = metric.values || {};
  return Number(values[valueName] || 0);
}

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
