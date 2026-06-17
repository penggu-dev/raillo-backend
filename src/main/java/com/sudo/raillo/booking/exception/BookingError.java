package com.sudo.raillo.booking.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingError implements ErrorCode {

	SEAT_NOT_FOUND("좌석을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_001"),
	TRAIN_NOT_OPERATIONAL("운행중인 스케줄이 아닙니다.", HttpStatus.BAD_REQUEST, "B_011"),
	BOOKING_CREATE_SEATS_INVALID("좌석 수는 총 승객 수와 같아야 합니다.", HttpStatus.BAD_REQUEST, "B_012"),
	SEAT_BOOKING_NOT_FOUND("좌석 예약 상태를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_018"),
	TICKET_NOT_FOUND("티켓을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_019"),
	TICKET_ACCESS_DENIED("해당 티켓에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "B_022"),
	TICKET_NOT_USABLE("사용할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "B_023"),
	TICKET_NOT_CANCELLABLE("취소할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "B_024"),
	BOOKING_ALREADY_CANCELLED("이미 취소된 좌석입니다", HttpStatus.BAD_REQUEST, "B_021"),

	// 예약 요청 Request 관련
	INVALID_CAR_TYPE("좌석의 객차 타입은 동일해야 합니다.", HttpStatus.BAD_REQUEST, "B_016"),

	// 예매(승차권) 조회 관련
	BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_101"),
	INVALID_BOOKING_TIME_FILTER("유효하지 않은 조회 필터입니다. 허용 값: upcoming, history, all", HttpStatus.BAD_REQUEST, "B_102"),

	// PENDING_BOOKING 관련
	PENDING_BOOKING_ACCESS_DENIED("해당 임시 예약에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "B302"),
	PENDING_BOOKING_IDS_REQUIRED("조회할 임시 예약 ID 목록이 필요합니다.", HttpStatus.BAD_REQUEST, "B303"),
	PENDING_BOOKING_EXPIRED("만료된 임시 예약이 있습니다. 다시 예약해주세요.", HttpStatus.BAD_REQUEST, "B304"),
	INVALID_PENDING_BOOKING_TTL("임시 예약을 처리할 수 없습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B305"),

	// 좌석 충돌 관련
	SEAT_CONFLICT_WITH_SOLD("이미 판매된 좌석이 존재하는 구간입니다.", HttpStatus.CONFLICT, "B_304"),
	SEAT_CONFLICT_WITH_HOLD("다른 사용자가 임시 점유 중인 구간입니다.", HttpStatus.CONFLICT, "B_305"),

	// Seat Hold 관련
	SEAT_HOLD_SCRIPT_ERROR("좌석 점유 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_306"),
	SEAT_HOLD_RELEASE_FAILED("좌석 점유 해제에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_309"),

	// 영수증 관련
	RECEIPT_NOT_FOUND("영수증 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B401");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
