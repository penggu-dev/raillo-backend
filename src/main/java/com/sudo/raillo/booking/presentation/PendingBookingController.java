package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.PendingBookingCreateResponse;
import com.sudo.raillo.booking.application.dto.response.PendingBookingDetail;
import com.sudo.raillo.booking.application.facade.PendingBookingFacade;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.docs.PendingBookingControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/pending-bookings")
@RequiredArgsConstructor
public class PendingBookingController implements PendingBookingControllerDoc {

	private final PendingBookingFacade pendingBookingFacade;
	private final PendingBookingService pendingBookingService;

	/***
	 * 임시예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 생성 성공 응답
	 */
	@PostMapping
	public SuccessResponse<PendingBookingCreateResponse> createPendingBooking(
		@RequestBody @Valid PendingBookingCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		PendingBookingCreateResponse response = pendingBookingFacade
			.createPendingBooking(request, userDetails.getUsername());
		return SuccessResponse.of(BookingSuccess.BOOKING_CREATE_SUCCESS, response);
	}

	/**
	 * 임시예약 목록 조회 메서드
	 * @return 회원의 임시 예약 목록 응답
	 * */
	@GetMapping
	public SuccessResponse<List<PendingBookingDetail>> getPendingBookings(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<PendingBookingDetail> response = pendingBookingService.getPendingBookings(memberNo);
		return SuccessResponse.of(BookingSuccess.BOOKING_LIST_SUCCESS, response);
	}

}
