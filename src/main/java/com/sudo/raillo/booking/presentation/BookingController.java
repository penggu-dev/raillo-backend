package com.sudo.raillo.booking.presentation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ProvisionalBookingResponse;
import com.sudo.raillo.booking.application.facade.ReservationFacade;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "예약 API", description = "예약 관련 API")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

	private final ReservationFacade reservationFacade;

	@PostMapping("/provisional")
	@Operation(summary = "임시 예약 생성", description = "좌석 선택 후 10분간 유효한 임시 예약을 생성합니다.")
	public ResponseEntity<ProvisionalBookingResponse> createProvisionalBooking(
		@RequestBody @Valid ReservationCreateRequest request,
		@AuthenticationPrincipal String memberNo
	) {
		ProvisionalBookingResponse response = reservationFacade.createProvisionalBooking(request, memberNo);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/provisional/{bookingId}")
	@Operation(summary = "임시 예약 취소")
	public ResponseEntity<Void> cancelProvisionalBooking(
		@PathVariable String bookingId
	) {
		reservationFacade.cancelProvisionalBooking(bookingId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{reservationId}")
	@Operation(summary = "확정 예약 취소")
	public ResponseEntity<Void> cancelReservation(
		@PathVariable Long reservationId,
		@AuthenticationPrincipal String memberNo
	) {
		reservationFacade.cancelReservation(reservationId, memberNo);
		return ResponseEntity.noContent().build();
	}
}
