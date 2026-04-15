import http from 'k6/http';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SEARCH_URL = `${BASE_URL}/api/v1/trains/search?page=0&size=10`;
const CARS_URL = `${BASE_URL}/api/v1/trains/cars`;
const SEATS_URL = `${BASE_URL}/api/v1/trains/seats`;
const SCHEDULES = JSON.parse(open('./schedule-config.json'));
const searchDuration = new Trend('search');
const carsDuration = new Trend('cars');
const seatsDuration = new Trend('seats');

const params = {
    headers: {
        'Content-Type': 'application/json',
    },
};

export const options = {
    stages: [
        { duration: '5m', target: 300 },
    ],
};

export default function () {
    const turn = __ITER % 10;

    if (turn < 6) {
        const res = http.post(SEARCH_URL, JSON.stringify(createSearchPayload()), params);
        searchDuration.add(res.timings.duration);
        return;
    }

    const schedule = getRandomItem(SCHEDULES);

    if (turn === 6) {
        const res = http.post(CARS_URL, JSON.stringify({
            trainScheduleId: schedule.scheduleId,
            departureStationId: schedule.departureStation,
            arrivalStationId: schedule.arrivalStation,
            passengerCount: 1,
        }), params);
        carsDuration.add(res.timings.duration);
        return;
    }

    const res = http.post(SEATS_URL, JSON.stringify({
        trainCarId: randomInt(1, 6),
        trainScheduleId: schedule.scheduleId,
        departureStationId: schedule.departureStation,
        arrivalStationId: schedule.arrivalStation,
    }), params);
    seatsDuration.add(res.timings.duration);
}

function createSearchPayload() {
    return {
        departureStationId: randomInt(1, 2),
        arrivalStationId: 18,
        operationDate: randomOperationDate(),
        departureHour: String(randomInt(0, 23)).padStart(2, '0'),
        passengerCount: randomInt(1, 4),
    };
}

function randomOperationDate() {
    const date = new Date();
    date.setDate(date.getDate() + randomInt(1, 7));
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');

    return `${year}-${month}-${day}`;
}

function getRandomItem(items) {
    return items[randomInt(0, items.length - 1)];
}

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}
