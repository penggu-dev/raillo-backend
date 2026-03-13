package com.sudo.raillo.booking.application.metrics;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
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

	@Around("execution(* com.sudo.raillo.booking.application.facade.PendingBookingFacade.createPendingBooking(..))")
	public Object measurePendingBookingCreation(ProceedingJoinPoint joinPoint) throws Throwable {
		try {
			Object result = joinPoint.proceed();
			bookingMetrics.incrementPendingBookingCreated();
			return result;
		} catch (BusinessException e) {
			if(e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_HOLD) {
				bookingMetrics.incrementSeatConflictHold();
			} else if(e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_SOLD) {
				bookingMetrics.incrementSeatConflictSold();
			}
			throw e;
		}
	}
}
