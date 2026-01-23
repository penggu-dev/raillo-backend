import http from 'k6/http';

const API_URL = 'http://localhost:8080/api/v1/trains/search?page=0&size=10';

const REQUEST_BODY = {
  "departureStationId": 2,
  "arrivalStationId": 18,  
  "operationDate": "2026-02-15", // 오늘 날짜 이후 값으로 설정
  "departureHour": 9,
  "passengerCount": 2
};

export const options = {
  stages: [
    { duration: '5m', target: 300 }
  ]
};

export default function () {
  const payload = JSON.stringify(REQUEST_BODY);

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  http.post(API_URL, payload, params);
}
