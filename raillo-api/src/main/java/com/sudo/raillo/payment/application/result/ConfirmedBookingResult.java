package com.sudo.raillo.payment.application.result;

/** 주문의 예약 ID와 실제 생성된 예매 ID의 대응. */
public record ConfirmedBookingResult(String reservationId, long bookingId) {}
