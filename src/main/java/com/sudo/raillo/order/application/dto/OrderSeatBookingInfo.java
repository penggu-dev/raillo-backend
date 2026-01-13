package com.sudo.raillo.order.application.dto;

import com.sudo.raillo.booking.domain.type.PassengerType;
import java.math.BigDecimal;

public record OrderSeatBookingInfo(
	Long seatId,
	PassengerType passengerType,
	BigDecimal fare
) {
}
