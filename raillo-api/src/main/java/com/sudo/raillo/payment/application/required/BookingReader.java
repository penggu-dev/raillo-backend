package com.sudo.raillo.payment.application.required;

/** 결제 후속 처리에서 예매 상태를 확인하는 required port. */
public interface BookingReader {

	boolean isBooked(long bookingId);
}
