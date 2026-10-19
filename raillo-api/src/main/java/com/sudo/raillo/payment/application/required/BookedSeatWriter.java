package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.payment.application.BookingConfirmedPayload;

/** 결제 확정 후 Redis 좌석 점유를 예매 점유로 바꾸는 required port. */
public interface BookedSeatWriter {

	/** @return 전환했으면 true, 다른 예약이나 예매와 충돌해 아무것도 쓰지 않았으면 false */
	boolean markBooked(BookingConfirmedPayload.Entry entry);

	/** 예매가 되지 못한 예약의 좌석 점유와 예약 본문, 회원 인덱스를 되돌린다. */
	void releaseReservation(BookingConfirmedPayload.Entry entry);
}
