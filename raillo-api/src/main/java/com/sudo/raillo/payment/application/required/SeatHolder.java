package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.booking.domain.Reservation;

/**
 * 결제 결과를 모르는 동안의 예약 좌석 보호 required port.
 */
public interface SeatHolder {

	/**
	 * 예약의 좌석 field 만료를 없애 결제가 끝날 때까지 붙잡는다.
	 *
	 * @return 붙잡았으면 true, 다른 예약이나 예매와 충돌해 아무것도 쓰지 않았으면 false
	 */
	boolean hold(Reservation reservation);

	/** 좌석 field 만료를 보호 이전으로 되돌린다. 되돌릴 기한이 없으면 좌석을 회수한다. */
	void releaseHold(Reservation reservation);
}
