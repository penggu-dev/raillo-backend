package com.sudo.raillo.booking.application.metrics;

import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class BookingMetricsAspect {

	private final BookingMetrics bookingMetrics;

	@AfterReturning("execution(* com.sudo.raillo.booking.application.facade.PendingBookingFacade.createPendingBooking(..))")
	public void countPendingBookingCreated() {
		bookingMetrics.incrementPendingBookingCreated();
	}

	@AfterThrowing(
		pointcut = "execution(* com.sudo.raillo.booking.application.facade.PendingBookingFacade.createPendingBooking(..))",
		throwing = "e")
	public void countSeatConflict(BusinessException e) {
		if (e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_HOLD) {
			bookingMetrics.incrementSeatConflictHold();
		} else if (e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_SOLD) {
			bookingMetrics.incrementSeatConflictSold();
		}
	}
}
