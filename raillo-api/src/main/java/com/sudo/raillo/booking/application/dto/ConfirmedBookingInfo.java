package com.sudo.raillo.booking.application.dto;

/** 주문 예약과 실제 생성된 예매의 명시적인 연결. */
public record ConfirmedBookingInfo(String reservationId, long bookingId) {}
