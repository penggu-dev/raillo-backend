package com.sudo.raillo.payment.application.required;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.application.result.ConfirmedBookingResult;
import java.util.List;

/**
 * 결제 승인 시 Booking 확정 required port.
 */
public interface BookingCreator {

	List<ConfirmedBookingResult> createBookingFromOrder(Order order);
}
