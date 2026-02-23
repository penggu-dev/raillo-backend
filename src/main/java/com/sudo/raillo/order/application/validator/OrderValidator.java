package com.sudo.raillo.order.application.validator;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.order.exception.OrderError;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderValidator {

	/**
	 * 예약 목록이 비어있는지 검증
	 */
	public void validatePendingBookingsNotEmpty(List<PendingBooking> pendingBookings) {
		if (pendingBookings == null || pendingBookings.isEmpty()) {
			log.error("[주문 검증 실패] 빈 예약 리스트로 주문 시도");
			throw new BusinessException(OrderError.EMPTY_PENDING_BOOKINGS);
		}
	}
}
