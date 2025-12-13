package com.sudo.raillo.order.infrastructure;

import com.sudo.raillo.order.domain.OrderSeatBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderSeatBookingRepository extends JpaRepository<OrderSeatBooking, Long> {
}
