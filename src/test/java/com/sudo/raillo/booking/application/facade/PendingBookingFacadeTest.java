package com.sudo.raillo.booking.application.facade;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.PendingBookingFixture;

@ServiceTest
class PendingBookingFacadeTest {

	@Autowired
	private PendingBookingFacade pendingBookingFacade;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	// PendingBookingFacadeTest로 이동
	@Test
	@DisplayName("권한이 없는 예약을 삭제하려고 시도하면 예외가 발생한다")
	void deletePendingBookings_fail_notOwner() {
		// given
		String ownerMemberNo = "owner_member_no";
		String nonOwnerMemberNo = "non_owner_member_no";

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(ownerMemberNo)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() ->
			pendingBookingFacade.deletePendingBookings(
				List.of(pendingBooking.getId()),
				nonOwnerMemberNo
			)).isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_ACCESS_DENIED);
	}
}
