package com.sudo.raillo.train.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TrainErrorCode implements ErrorCode {

	// 열차 조회 관련
	TRAIN_SCHEDULE_NOT_FOUND("해당 날짜에 운행하는 열차가 없습니다.", HttpStatus.NOT_FOUND, "T4001"),
	TRAIN_OPERATION_CANCELLED("해당 열차는 운행이 취소되었습니다.", HttpStatus.BAD_REQUEST, "T4002"),
	TRAIN_SCHEDULE_DETAIL_NOT_FOUND("해당 열차 스케줄을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "T4004"),
	NO_AVAILABLE_CARS("잔여 좌석이 있는 객차가 없습니다.", HttpStatus.NOT_FOUND, "T_4005"),
	TRAIN_CAR_NOT_FOUND("해당 객차를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "T_4006"),

	// 좌석 예약 관련
	SEAT_NOT_FOUND("좌석을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "T4106"),

	// 역 및 구간 관련
	STATION_NOT_FOUND("존재하지 않는 역입니다.", HttpStatus.NOT_FOUND, "T4201"),
	STATION_FARE_NOT_FOUND("해당 구간의 요금 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "T4202"),
	INVALID_ROUTE("출발역과 도착역이 동일하거나 유효하지 않은 경로입니다.", HttpStatus.BAD_REQUEST, "T4203"),
	SCHEDULE_STOP_NOT_FOUND("정류장 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "T4204"),

	// 날짜, 시간 관련
	OPERATION_DATE_TOO_FAR("예약 가능한 기간을 초과했습니다. (최대 1개월)", HttpStatus.BAD_REQUEST, "T4402"),
	DEPARTURE_TIME_PASSED("선택하신 열차의 출발 시간이 이미 지났습니다.", HttpStatus.BAD_REQUEST, "T4404"),

	// 검색 관련
	NO_SEARCH_RESULTS("검색 조건에 맞는 열차가 없습니다.", HttpStatus.NOT_FOUND, "T4501");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
