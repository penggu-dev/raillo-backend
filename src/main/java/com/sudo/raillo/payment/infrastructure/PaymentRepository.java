package com.sudo.raillo.payment.infrastructure;

import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/**
	 * 결제 키로 결제 정보 조회
	 */
	Optional<Payment> findByPaymentKey(String paymentKey);

	/**
	 * 예약 ID와 결제 상태로 결제 존재 여부 확인
	 */
	boolean existsByBookingIdAndPaymentStatus(Long bookingId, PaymentStatus paymentStatus);

	Optional<Payment> findByOrder(Order order);

	boolean existsByOrderAndPaymentStatus(Order order, PaymentStatus status);
}
