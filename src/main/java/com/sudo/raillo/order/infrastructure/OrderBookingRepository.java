package com.sudo.raillo.order.infrastructure;

import com.sudo.raillo.order.domain.OrderBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderBookingRepository extends JpaRepository<OrderBooking, Long> {
}
