package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.BookingCreateRequest;
import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingCreateResponse;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.facade.BookingFacade;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.docs.BookingControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/booking")
@RequiredArgsConstructor
public class BookingController implements BookingControllerDoc {

	private final BookingFacade bookingFacade;
	private final BookingService bookingService;

	/***
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 생성 성공 응답
	 */
	@PostMapping
	public SuccessResponse<BookingCreateResponse> createBooking(
		@RequestBody BookingCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		BookingCreateResponse response = bookingFacade
			.createBooking(request, userDetails.getUsername());
		return SuccessResponse.of(BookingSuccess.BOOKING_CREATE_SUCCESS, response);
	}

	/***
	 * 예약을 삭제하는 메서드
	 * @param request 예약 삭제 요청 DTO
	 * @return 예약 삭제 성공 응답
	 */
	@DeleteMapping
	public SuccessResponse<?> deleteBooking(@RequestBody BookingDeleteRequest request) {
		bookingService.deleteBooking(request.bookingId());
		return SuccessResponse.of(BookingSuccess.BOOKING_DELETE_SUCCESS);
	}

	/**
	 * 예약을 조회하는 메서드
	 */
	@GetMapping("/{bookingId}")
	public SuccessResponse<BookingDetail> getBooking(
		@PathVariable Long bookingId,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		BookingDetail detail = bookingService.getBooking(memberNo, bookingId);
		return SuccessResponse.of(BookingSuccess.BOOKING_DETAIL_SUCCESS, detail);
	}

	/**
	 * 예약 목록을 조회하는 메서드
	 */
	@GetMapping
	public SuccessResponse<List<BookingDetail>> getBookings(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<BookingDetail> response = bookingService.getBookings(memberNo);
		return SuccessResponse.of(BookingSuccess.BOOKING_LIST_SUCCESS, response);
	}
}
