package com.sudo.raillo.booking.application.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.ReservationService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReservationScheduler {

	private final ReservationService reservationService;

	@Scheduled(cron = "0 0 * * * *") // 매 시간마다 실행
	public void expireReservations() {
		try {
			reservationService.expireReservations();
		} catch (Exception e) {
			// TODO: 로깅
		}
	}
}
