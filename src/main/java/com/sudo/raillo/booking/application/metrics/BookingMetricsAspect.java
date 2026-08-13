package com.sudo.raillo.booking.application.metrics;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class BookingMetricsAspect {

	private final BookingMetrics bookingMetrics;

	@Around("execution(* com.sudo.raillo.booking.application.facade.ReservationFacade.createReservation(..))")
	public Object measureReservationCreation(ProceedingJoinPoint joinPoint) throws Throwable {
		Sample sample = Timer.start();
		try {
			Object result = joinPoint.proceed();
			bookingMetrics.incrementReservationCreated();
			return result;
		} catch (BusinessException e) {
			if (e.getErrorCode() == BookingError.SEAT_ALREADY_OCCUPIED) {
				bookingMetrics.incrementSeatConflictOccupied();
			}
			throw e;
		} finally {
			sample.stop(bookingMetrics.getReservationTimer());
		}
	}

	@Around("execution(* com.sudo.raillo.booking.application.service.SeatOccupancyService.hold(..))")
	public Object timeSeatOccupancy(ProceedingJoinPoint joinPoint) throws Throwable {
		return recordTime(joinPoint, bookingMetrics.getSeatOccupancyTimer());
	}

	private Object recordTime(ProceedingJoinPoint joinPoint, Timer timer) throws Throwable {
		Sample sample = Timer.start();
		try {
			return joinPoint.proceed();
		} finally {
			sample.stop(timer);
		}
	}
}
