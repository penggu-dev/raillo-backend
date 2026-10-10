package com.sudo.raillo.booking.presentation;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.docs.BookingControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.response.SuccessResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController implements BookingControllerDoc {

	private final BookingService bookingService;

	/***
	 * 본인 `Booking`을 삭제한다.
	 * @param request 삭제 요청 DTO
	 * @param userDetails 로그인한 회원
	 * @return 삭제 성공 응답
	 */
	@DeleteMapping
	public SuccessResponse<?> deleteBooking(
		@Valid @RequestBody BookingDeleteRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		bookingService.deleteBooking(userDetails.getUsername(), request.bookingId());
		return SuccessResponse.of(BookingSuccess.BOOKING_DELETE_SUCCESS);
	}

	/**
	 * 승차권 상세 조회
	 */
	@GetMapping("/{bookingId}")
	public SuccessResponse<BookingResponse> getBooking(
		@PathVariable Long bookingId,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		BookingResponse detail = bookingService.getBooking(memberNo, bookingId);
		return SuccessResponse.of(BookingSuccess.BOOKING_DETAIL_SUCCESS, detail);
	}

	/**
	 * 승차권 목록 조회
	 */
	@GetMapping
	public SuccessResponse<List<BookingResponse>> getBookings(
		@RequestParam(required = false, defaultValue = "ALL") BookingTimeFilter status,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<BookingResponse> response = bookingService.getBookings(memberNo, status);
		return SuccessResponse.of(BookingSuccess.BOOKING_LIST_SUCCESS, response);
	}
}
