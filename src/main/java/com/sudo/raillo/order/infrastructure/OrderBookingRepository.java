package com.sudo.raillo.order.infrastructure;

import java.util.List;
import java.util.Optional;

import com.sudo.raillo.order.domain.OrderBooking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderBookingRepository extends JpaRepository<OrderBooking, Long> {
	List<OrderBooking> findByOrderId(Long orderId);

	@Query("SELECT ob FROM OrderBooking ob JOIN FETCH ob.reservation WHERE ob.order.id = :orderId")
	List<OrderBooking> findByOrderIdWithReservation(Long orderId);
}
