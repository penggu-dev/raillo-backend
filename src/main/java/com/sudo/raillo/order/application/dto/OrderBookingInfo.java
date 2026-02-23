package com.sudo.raillo.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderBookingInfo(
	String pendingBookingId,
	Long trainScheduleId,
	Long departureStopId,
	Long arrivalStopId,
	BigDecimal totalFare,
	List<OrderSeatBookingInfo> seatInfos
) {
}
