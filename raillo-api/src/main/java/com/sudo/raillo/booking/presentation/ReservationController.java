package com.sudo.raillo.booking.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.docs.ReservationControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.response.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController implements ReservationControllerDoc {

	private final ReservationFacade reservationFacade;

	@PostMapping
	public SuccessResponse<ReservationCreateResponse> createReservation(
		@RequestBody @Valid ReservationCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		ReservationCreateResponse response = reservationFacade.createReservation(request, userDetails.getUsername());
		return SuccessResponse.of(BookingSuccess.RESERVATION_CREATE_SUCCESS, response);
	}
}
