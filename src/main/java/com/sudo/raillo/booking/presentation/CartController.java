package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.CartCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.service.CartService;
import com.sudo.raillo.booking.docs.CartControllerDoc;
import com.sudo.raillo.booking.success.CartSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/cart/bookings")
@RequiredArgsConstructor
public class CartController implements CartControllerDoc {

	private final CartService cartService;

	@PostMapping
	public SuccessResponse<?> createCart(
		@Valid @RequestBody CartCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		cartService.createCart(memberNo, request);
		return SuccessResponse.of(CartSuccess.CART_CREATE_SUCCESS);
	}

	@GetMapping
	public SuccessResponse<List<BookingResponse>> getCarts(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<BookingResponse> response = cartService.getCarts(memberNo);
		return SuccessResponse.of(CartSuccess.CART_LIST_SUCCESS, response);
	}
}
