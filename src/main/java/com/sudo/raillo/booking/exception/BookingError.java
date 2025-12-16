package com.sudo.raillo.booking.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingError implements ErrorCode {

	SEAT_NOT_FOUND("좌석을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_001"),
	SEAT_ALREADY_BOOKED("이미 예약된 좌석입니다.", HttpStatus.CONFLICT, "B_002"),
	SEAT_BOOKING_FAILED("좌석 예약에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_003"),
	SEAT_CANCELLATION_FAILED("좌석 취소에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_004"),
	SEAT_ALREADY_CANCELLED("이미 취소된 좌석입니다.", HttpStatus.CONFLICT, "B_005"),
	SEAT_NOT_AVAILABLE("사용가능한 좌석이 아닙니다.", HttpStatus.BAD_REQUEST, "B_006"),
	SEAT_NOT_BOOKED("예약된 좌석이 아닙니다.", HttpStatus.BAD_REQUEST, "B_007"),
	EXPIRED_SEAT_CANCELLATION_FAILED("만료된 좌석 취소에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_008"),
	BOOKING_CREATE_FAILED("예약에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_009"),
	BOOKING_DELETE_FAILED("예약 취소에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_010"),
	TRAIN_NOT_OPERATIONAL("운행중인 스케줄이 아닙니다.", HttpStatus.BAD_REQUEST, "B_011"),
	BOOKING_CREATE_SEATS_INVALID("좌석 수는 총 승객 수와 같아야 합니다.", HttpStatus.BAD_REQUEST, "B_012"),
	TICKET_CREATE_FAILED("티켓 생성에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_014"),
	TICKET_LIST_GET_FAILED("티켓을 가져올 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_015"),
	BOOKING_EXPIRED("예약이 만료되었습니다.", HttpStatus.GONE, "B_017"),
	SEAT_BOOKING_NOT_FOUND("좌석 예약 상태를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_018"),
	TICKET_NOT_FOUND("티켓을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_019"),
	INVALID_TOTAL_FAIR("예약 총 금액은 0보다 크거나 같아야 합니다", HttpStatus.BAD_REQUEST, "B_020"),
	BOOKING_ALREADY_CANCELLED("이미 취소된 좌석입니다", HttpStatus.BAD_REQUEST, "B_021"),

	// 예약 요청 Request 관련
	INVALID_CAR_TYPE("좌석의 객차 타입은 동일해야 합니다.", HttpStatus.BAD_REQUEST, "B_016"),

	// 예약 조회 관련
	BOOKING_NOT_FOUND("예약 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_101"),

	// 장바구니 관련
	BOOKING_ACCESS_DENIED("본인의 예약만 장바구니에 등록할 수 있습니다.", HttpStatus.FORBIDDEN, "B_201"),
	BOOKING_ALREADY_EXISTED("이미 등록된 예약입니다.", HttpStatus.CONFLICT, "B_202"),

	// PENDING_BOOKING 관련
	PENDING_BOOKING_NOT_FOUND("임시 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B301"),
	PENDING_BOOKING_ACCESS_DENIED("해당 임시 예약에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "B302"),
	PENDING_BOOKING_IDS_REQUIRED("조회할 임시 예약 ID 목록이 필요합니다.", HttpStatus.BAD_REQUEST, "B303");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
