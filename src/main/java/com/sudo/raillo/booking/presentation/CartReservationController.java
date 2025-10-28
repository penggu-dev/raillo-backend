package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.service.CartReservationService;
import com.sudo.raillo.booking.application.dto.request.CartReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.docs.CartReservationControllerDoc;
import com.sudo.raillo.booking.success.CartReservationSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/cart/reservations")
@RequiredArgsConstructor
public class CartReservationController implements CartReservationControllerDoc {

	private final CartReservationService cartReservationService;

	@PostMapping
	public SuccessResponse<?> createCartReservation(
		@Valid @RequestBody CartReservationCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		cartReservationService.createCartReservation(memberNo, request);
		return SuccessResponse.of(CartReservationSuccess.CART_RESERVATION_CREATE_SUCCESS);
	}

	@GetMapping
	public SuccessResponse<List<ReservationDetail>> getCartReservations(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<ReservationDetail> response = cartReservationService.getCartReservations(memberNo);
		return SuccessResponse.of(CartReservationSuccess.CART_RESERVATION_LIST_SUCCESS, response);
	}
}
