package com.sudo.raillo.payment.application.outbox;

import com.sudo.raillo.payment.domain.PaymentOutboxType;

/**
 * 예매 점유 확정의 후속 PR 구현 지점. 아직 Spring 처리기로 등록하지 않는다.
 *
 * <p>결제 시작 전에 자기 R:{reservationId} field의 TTL을 제거하여 보호한다.
 * DB 승인/예매/Outbox 커밋 뒤에는 사용자가 아래 후처리를 기다리지 않는다.
 *
 * <p>후속 구현: payload snapshot과 attempt 소유권 검증 → R을 B:{bookingId}로 원자 전환
 * → field TTL 없음 보장(운행 Hash 키 만료 유지) → 예약 본문/보호 marker 정리
 * → 별도 slot 회원 인덱스 정리. 같은 B는 멱등 성공, 다른 R/B는 변경 금지.
 * 빈 field는 취소된 예매일 수도 있으므로 무조건 복원하지 않는다.
 * Redis 반영 후 DONE 커밋 실패 및 여러 운행의 부분 성공도 안전하게 재처리해야 한다.
 *
 * <p>구현 전에는 worker의 지원 타입 조회에서 제외되어 PENDING으로 보존된다.
 */
public class BookingConfirmedProcessor implements OutboxEventProcessor {
	@Override
	public boolean supports(PaymentOutboxType type) {
		return type == PaymentOutboxType.BOOKING_CONFIRMED;
	}

	@Override
	public void process(String payload) {
		throw new UnsupportedOperationException("Reservation의 R→B 확정은 후속 PR에서 구현합니다");
	}
}
