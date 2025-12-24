package com.sudo.raillo.booking.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.facade.PendingBookingFacade;
import com.sudo.raillo.booking.docs.PendingBookingControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/pending-bookings")
@RequiredArgsConstructor
public class PendingBookingController implements PendingBookingControllerDoc {

	private final PendingBookingFacade pendingBookingFacade;

	/***
	 * 임시예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 생성 성공 응답
	 */
	@PostMapping
	public SuccessResponse<PendingBookingCreateResponse> createPendingBooking(
		@RequestBody PendingBookingCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		PendingBookingCreateResponse response = pendingBookingFacade
			.createPendingBooking(request, userDetails.getUsername());
		return SuccessResponse.of(BookingSuccess.BOOKING_CREATE_SUCCESS, response);
	}
}
