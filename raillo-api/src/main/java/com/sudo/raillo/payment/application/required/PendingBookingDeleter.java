package com.sudo.raillo.payment.application.required;

import java.util.List;

/**
 * PendingBooking 삭제 required port. Outbox Worker의 정리 흐름에서 사용된다.
 */
public interface PendingBookingDeleter {

	void deletePendingBookings(List<String> pendingBookingIds, String memberNo);
}
