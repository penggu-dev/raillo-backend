package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.order.domain.Order;

/**
 * Order 도메인 접점 required port.
 *
 * <p>결제 유스케이스에 필요한 Order 관련 오퍼레이션만 노출한다.
 */
public interface OrderRegister {

	Order createOrder(String memberNo, List<PendingBooking> pendingBookings);

	Order getOrderByOrderCode(String orderCode);

	List<String> getPendingBookingIds(Order order);

	void validateOrderOwner(Order order, Member member);
}
