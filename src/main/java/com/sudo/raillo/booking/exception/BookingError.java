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
	TICKET_ACCESS_DENIED("해당 티켓에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "B_022"),
	TICKET_NOT_USABLE("사용할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "B_023"),
	TICKET_NOT_CANCELLABLE("취소할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "B_024"),
	INVALID_TOTAL_FAIR("예약 총 금액은 0보다 크거나 같아야 합니다", HttpStatus.BAD_REQUEST, "B_020"),
	BOOKING_ALREADY_CANCELLED("이미 취소된 좌석입니다", HttpStatus.BAD_REQUEST, "B_021"),

	// 예약 요청 Request 관련
	INVALID_CAR_TYPE("좌석의 객차 타입은 동일해야 합니다.", HttpStatus.BAD_REQUEST, "B_016"),

	// 예매(승차권) 조회 관련
	BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_101"),
	INVALID_BOOKING_TIME_FILTER("유효하지 않은 조회 필터입니다. 허용 값: upcoming, history, all", HttpStatus.BAD_REQUEST, "B_102"),


	// PENDING_BOOKING 관련
	PENDING_BOOKING_NOT_FOUND("임시 예약을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B301"),
	PENDING_BOOKING_ACCESS_DENIED("해당 임시 예약에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "B302"),
	PENDING_BOOKING_IDS_REQUIRED("조회할 임시 예약 ID 목록이 필요합니다.", HttpStatus.BAD_REQUEST, "B303"),
	PENDING_BOOKING_EXPIRED("만료된 임시 예약이 있습니다. 다시 예약해주세요.", HttpStatus.BAD_REQUEST, "B304"),

	// BookingError.java에 추가할 부분

	// 좌석 충돌 관련 (기존 SEAT 관련 에러 아래에 추가)
	SEAT_CONFLICT_WITH_HOLD("다른 사용자가 임시 점유 중인 구간입니다.", HttpStatus.CONFLICT, "B_304"),

	// 좌석 Hold 관련
	SEAT_HOLD_SCRIPT_ERROR("좌석 점유 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_306"),
	SEAT_HOLD_NOT_FOUND("임시 점유 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_307"),
	SEAT_HOLD_CONFIRM_FAILED("좌석 확정 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_308"),
	SEAT_HOLD_RELEASE_FAILED("좌석 점유 해제에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "B_309"),
	SEAT_HOLD_SECTION_NOT_FOUND("좌석 점유 구간을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B_310"),

	// 영수증 관련
	RECEIPT_NOT_FOUND("영수증 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "B401");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
