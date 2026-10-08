package com.sudo.raillo.booking.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.global.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BookingError implements ErrorCode {

	// 예매/좌석예약 (1xx)
	BOOKING_NOT_FOUND("예매 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_101"),
	BOOKING_ALREADY_CANCELLED("이미 취소된 좌석입니다", HttpStatus.BAD_REQUEST, "BOOKING_102"),
	BOOKING_CREATE_SEATS_INVALID("좌석 수는 총 승객 수와 같아야 합니다.", HttpStatus.BAD_REQUEST, "BOOKING_103"),
	MULTIPLE_TRAIN_CARS("좌석은 한 객차 안에서만 선택할 수 있습니다.", HttpStatus.BAD_REQUEST, "BOOKING_104"),
	TRAIN_NOT_OPERATIONAL("운행중인 스케줄이 아닙니다.", HttpStatus.BAD_REQUEST, "BOOKING_105"),
	INVALID_BOOKING_TIME_FILTER("유효하지 않은 조회 필터입니다. 허용 값: upcoming, history, all", HttpStatus.BAD_REQUEST, "BOOKING_106"),
	SEAT_BOOKING_NOT_FOUND("좌석 예매 상태를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_107"),
	DUPLICATE_SEAT_IDS("같은 좌석을 중복 선택할 수 없습니다.", HttpStatus.BAD_REQUEST, "BOOKING_108"),

	// 승차권 (2xx)
	TICKET_NOT_FOUND("티켓을 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_201"),
	TICKET_ACCESS_DENIED("해당 티켓에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "BOOKING_202"),
	TICKET_NOT_USABLE("사용할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "BOOKING_203"),
	TICKET_NOT_CANCELLABLE("취소할 수 없는 티켓입니다.", HttpStatus.BAD_REQUEST, "BOOKING_204"),

	// 예약(Reservation) (3xx)
	RESERVATION_ACCESS_DENIED("해당 예약에 대한 접근 권한이 없습니다.", HttpStatus.FORBIDDEN, "BOOKING_305"),
	RESERVATION_IDS_REQUIRED("조회할 예약 ID 목록이 필요합니다.", HttpStatus.BAD_REQUEST, "BOOKING_306"),
	RESERVATION_EXPIRED("만료된 예약이 있습니다. 다시 예약해주세요.", HttpStatus.BAD_REQUEST, "BOOKING_307"),

	DUPLICATE_RESERVATION_IDS("예약 ID를 중복 지정할 수 없습니다.", HttpStatus.BAD_REQUEST, "BOOKING_309"),

	// 좌석 점유·충돌 (4xx)
	SEAT_CONFLICT_WITH_BOOKING("이미 예매된 좌석이 있는 구간입니다.", HttpStatus.CONFLICT, "BOOKING_401"),
	SEAT_CONFLICT_WITH_RESERVATION("다른 사용자가 예약 중인 구간입니다.", HttpStatus.CONFLICT, "BOOKING_402"),
	SEAT_OCCUPANCY_SCRIPT_ERROR("좌석 점유 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_403"),
	SEAT_OCCUPANCY_RELEASE_FAILED("좌석 점유 해제에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_404"),
	SEAT_OCCUPANCY_CORRUPTED("좌석 점유 데이터가 손상되었습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_405"),

	// 영수증 (5xx)
	RECEIPT_NOT_FOUND("영수증 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "BOOKING_501");

	private final String message;
	private final HttpStatus status;
	private final String code;

	/**
	 * 좌석 점유 데이터 오염은 재시도로 낫지 않는다. Redis에 이미 들어 있는 값이 좌석 점유 값이 아니므로
	 * 같은 스크립트를 몇 번 더 실행해도 같은 응답이 돌아온다. 사람이 그 값을 치워야 한다.
	 *
	 * <p>여기서 재시도 대상은 <b>그 코드로 실패한 작업을 다시 실행하는 것</b>이다. 판정 단위가 에러 코드
	 * 하나이므로, 한 코드가 일시적 원인과 영구적 원인을 함께 덮으면 둘을 가를 수 없다.
	 * {@link #SEAT_OCCUPANCY_SCRIPT_ERROR}가 그 경우다 — Redis 연결 끊김·타임아웃(재시도가 낫게 한다)과
	 * Lua 버그·응답 계약 위반(배포 없이는 안 낫는다)이 같은 코드로 올라온다. 의도한 한계이며, 재시도 가능으로
	 * 두는 쪽이 틀렸을 때 손실이 작기 때문이다(백오프 한 바퀴 낭비 대 복구될 건의 조기 포기). 구분하려면
	 * 좌석 점유 스크립트들의 응답 계약에 실패 코드를 늘려야 한다.</p>
	 */
	@Override
	public boolean failedWorkRetryable() {
		return this != SEAT_OCCUPANCY_CORRUPTED;
	}
}
