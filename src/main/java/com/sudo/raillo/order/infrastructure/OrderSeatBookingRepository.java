package com.sudo.raillo.order.infrastructure;

import com.sudo.raillo.order.domain.OrderSeatBooking;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderSeatBookingRepository extends JpaRepository<OrderSeatBooking, Long> {

	List<OrderSeatBooking> findByOrderBookingId(Long orderBookingId);

	@Query("SELECT osb FROM OrderSeatBooking osb WHERE osb.orderBooking.id IN :orderBookingIds")
	List<OrderSeatBooking> findByOrderBookingIds(@Param("orderBookingIds") List<Long> orderBookingIds);
}
