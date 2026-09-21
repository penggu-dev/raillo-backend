package com.sudo.raillo.payment.adapter.integration;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.payment.application.required.PendingBookingDeleter;
import com.sudo.raillo.payment.application.required.PendingBookingReader;

import lombok.RequiredArgsConstructor;

/**
 * PendingBookingReader·PendingBookingDeleter 두 required port를 동시에 구현한다.
 * 위임 대상(PendingBookingService)이 하나이므로 어댑터 하나로 관리한다.
 */
@Component
@RequiredArgsConstructor
public class PendingBookingAdapter implements PendingBookingReader, PendingBookingDeleter {

	private final PendingBookingService pendingBookingService;

	@Override
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds, String memberNo) {
		return pendingBookingService.getPendingBookings(pendingBookingIds, memberNo);
	}

	@Override
	public void deletePendingBookings(List<String> pendingBookingIds, String memberNo) {
		pendingBookingService.deletePendingBookings(pendingBookingIds, memberNo);
	}
}
