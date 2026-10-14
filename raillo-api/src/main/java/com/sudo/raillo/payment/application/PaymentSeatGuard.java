package com.sudo.raillo.payment.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.required.OrderReader;
import com.sudo.raillo.payment.application.required.SeatHolder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 결제 결과를 모르는 동안 예약 좌석을 붙잡고, 예매 없이 끝나면 되돌린다.
 *
 * <p>좌석 보호의 실패와 충돌은 로그만 남기고 삼킨다. 보호는 결제 결과를 바꾸지 못하므로 호출자가 들고 있는 원래
 * 오류를 가려서는 안 된다. {@link AttemptFailureMarker}와 같은 이유다.
 *
 * <p>입력은 Redis 예약이 아니라 주문의 예약 스냅샷이다. 예약 본문이 만료된 뒤에도 해제에 필요한 좌석과 구간이 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSeatGuard {

	private final OrderReader orderReader;
	private final SeatHolder seatHolder;

	/** 결과를 모르는 동안 좌석 field의 만료를 없앤다. */
	public void holdQuietly(String orderCode, Long paymentId, Long attemptDbId) {
		try {
			List<Reservation> reservations = reservationsOf(orderCode);
			reservations.forEach(reservation -> {
				if (!seatHolder.hold(reservation)) {
					log.error("[결제 중 좌석 보호 실패 - 다른 점유와 충돌] paymentId={}, attemptDbId={}, reservationId={}",
						paymentId, attemptDbId, reservation.reservationId());
				}
			});
			log.info("[결제 중 좌석 보호] paymentId={}, attemptDbId={}, reservationIds={}",
				paymentId, attemptDbId, reservationIds(reservations));
		} catch (RuntimeException e) {
			log.error("[결제 중 좌석 보호 중 오류] paymentId={}, attemptDbId={}, orderCode={}",
				paymentId, attemptDbId, orderCode, e);
		}
	}

	/** 좌석 field의 만료를 보호 이전으로 되돌린다. */
	public void releaseQuietly(String orderCode, Long paymentId, Long attemptDbId) {
		try {
			List<Reservation> reservations = reservationsOf(orderCode);
			reservations.forEach(seatHolder::releaseHold);
			log.info("[결제 중 좌석 보호 해제] paymentId={}, attemptDbId={}, reservationIds={}",
				paymentId, attemptDbId, reservationIds(reservations));
		} catch (RuntimeException e) {
			log.error("[결제 중 좌석 보호 해제 중 오류] paymentId={}, attemptDbId={}, orderCode={}",
				paymentId, attemptDbId, orderCode, e);
		}
	}

	private List<Reservation> reservationsOf(String orderCode) {
		Order order = orderReader.getOrderByOrderCode(orderCode);
		return orderReader.getReservationSnapshots(order);
	}

	private static List<String> reservationIds(List<Reservation> reservations) {
		return reservations.stream().map(Reservation::reservationId).toList();
	}
}
