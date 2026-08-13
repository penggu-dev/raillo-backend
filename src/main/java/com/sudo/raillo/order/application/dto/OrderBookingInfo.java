package com.sudo.raillo.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderBookingInfo(
	String reservationCode,
	Long trainScheduleId,
	Long departureStopId,
	Long arrivalStopId,
	BigDecimal totalFare,
	List<OrderSeatBookingInfo> seatInfos
) {
}
