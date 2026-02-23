package com.sudo.raillo.support.helper;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import java.util.List;

public record OrderResult(
	Order order,
	List<OrderBooking> orderBookings,
	List<OrderSeatBooking> orderSeatBookings
) {
}
