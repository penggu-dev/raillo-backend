package com.sudo.raillo.booking.application.metrics;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.BusinessException;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import lombok.RequiredArgsConstructor;

@Aspect
@Component
@RequiredArgsConstructor
public class ReservationMetricsAspect {

	private final ReservationMetrics reservationMetrics;

	@Around("execution(* com.sudo.raillo.booking.application.facade.ReservationFacade.createReservation(..))")
	public Object measureReservationCreation(ProceedingJoinPoint joinPoint) throws Throwable {
		Sample sample = Timer.start();
		try {
			Object result = joinPoint.proceed();
			reservationMetrics.incrementReservationCreated();
			return result;
		} catch (BusinessException e) {
			if (e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_RESERVATION) {
				reservationMetrics.incrementSeatConflictWithReservation();
			} else if (e.getErrorCode() == BookingError.SEAT_CONFLICT_WITH_BOOKING) {
				reservationMetrics.incrementSeatConflictWithBooking();
			}
			throw e;
		} finally {
			sample.stop(reservationMetrics.getReservationTimer());
		}
	}

	@Around("execution(* com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository.occupy(..))")
	public Object timeSeatOccupancy(ProceedingJoinPoint joinPoint) throws Throwable {
		return recordTime(joinPoint, reservationMetrics.getSeatOccupancyTimer());
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
