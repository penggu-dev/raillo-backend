package com.sudo.raillo.payment.application.required;

import java.util.List;

import com.sudo.raillo.booking.domain.PendingBooking;

/**
 * PendingBooking 조회 required port.
 */
public interface PendingBookingReader {

	List<PendingBooking> getPendingBookings(List<String> pendingBookingIds, String memberNo);
}
