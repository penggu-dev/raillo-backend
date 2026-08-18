package com.sudo.raillo.booking.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingError implements ErrorCode {

	// 예매/좌석예약 (1xx)
	BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_101"),
	BOOKING_ALREADY_CANCELLED("이미 취소된 좌석입니다", HttpStatus.BAD_REQUEST, "BOOKING_102"),
	BOOKING_CREATE_SEATS_INVALID("좌석 수는 총 승객 수와 같아야 합니다.", HttpStatus.BAD_REQUEST, "BOOKING_103"),
	INVALID_CAR_TYPE("좌석의 객차 타입은 동일해야 합니다.", HttpStatus.BAD_REQUEST, "BOOKING_104"),
	TRAIN_NOT_OPERATIONAL("운행중인 스케줄이 아닙니다.", HttpStatus.BAD_REQUEST, "BOOKING_105"),
	INVALID_BOOKING_TIME_FILTER("유효하지 않은 조회 필터입니다. 허용 값: upcoming, history, all", HttpStatus.BAD_REQUEST, "BOOKING_106"),
	SEAT_BOOKING_NOT_FOUND("좌석 예매 상태를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_107"),

	// 승차권 (2xx)
	TICKET_NOT_FOUND("티켓을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_201"),
	TICKET_ACCESS_DENIED("해당 티켓에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "BOOKING_202"),
	TICKET_NOT_USABLE("사용할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "BOOKING_203"),
	TICKET_NOT_CANCELLABLE("취소할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "BOOKING_204"),

	// 예약(PendingBooking) (3xx)
	PENDING_BOOKING_ACCESS_DENIED("해당 예약에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "BOOKING_301"),
	PENDING_BOOKING_IDS_REQUIRED("조회할 예약 ID 목록이 필요합니다.", HttpStatus.BAD_REQUEST, "BOOKING_302"),
	PENDING_BOOKING_EXPIRED("만료된 예약이 있습니다. 다시 예약해주세요.", HttpStatus.BAD_REQUEST, "BOOKING_303"),
	INVALID_PENDING_BOOKING_TTL("예약을 처리할 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_304"),

	// 좌석 점유·충돌 (4xx)
	SEAT_CONFLICT_WITH_SOLD("이미 판매된 좌석이 존재하는 구간입니다.", HttpStatus.CONFLICT, "BOOKING_401"),
	SEAT_CONFLICT_WITH_HOLD("다른 사용자가 임시 점유 중인 구간입니다.", HttpStatus.CONFLICT, "BOOKING_402"),
	SEAT_HOLD_SCRIPT_ERROR("좌석 점유 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_403"),
	SEAT_HOLD_RELEASE_FAILED("좌석 점유 해제에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_404"),

	// 영수증 (5xx)
	RECEIPT_NOT_FOUND("영수증 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_501");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
