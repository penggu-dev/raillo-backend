package com.sudo.raillo.train.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TrainError implements ErrorCode {

	// 열차/스케줄 (1xx)
	TRAIN_SCHEDULE_NOT_FOUND("해당 날짜에 운행하는 열차가 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_101"),
	TRAIN_SCHEDULE_DETAIL_NOT_FOUND("해당 열차 스케줄을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_102"),
	TRAIN_OPERATION_CANCELLED("해당 열차는 운행이 취소되었습니다.", HttpStatus.BAD_REQUEST, "TRAIN_103"),

	// 객차/좌석 (2xx)
	NO_AVAILABLE_CARS("잔여 좌석이 있는 객차가 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_201"),
	TRAIN_CAR_NOT_FOUND("해당 객차를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_202"),
	SEAT_NOT_FOUND("좌석을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_203"),

	// 역/구간/운임 (3xx)
	STATION_NOT_FOUND("존재하지 않는 역입니다.", HttpStatus.NOT_FOUND, "TRAIN_301"),
	STATION_FARE_NOT_FOUND("해당 구간의 요금 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_302"),
	INVALID_ROUTE("출발역과 도착역이 동일하거나 유효하지 않은 경로입니다.", HttpStatus.BAD_REQUEST, "TRAIN_303"),
	SCHEDULE_STOP_NOT_FOUND("정류장 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_304"),

	// 날짜/검색 (4xx)
	OPERATION_DATE_TOO_FAR("예약 가능한 기간을 초과했습니다. (최대 1개월)", HttpStatus.BAD_REQUEST, "TRAIN_401"),
	DEPARTURE_TIME_PASSED("선택하신 열차의 출발 시간이 이미 지났습니다.", HttpStatus.BAD_REQUEST, "TRAIN_402"),
	NO_SEARCH_RESULTS("검색 조건에 맞는 열차가 없습니다.", HttpStatus.NOT_FOUND, "TRAIN_403");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
