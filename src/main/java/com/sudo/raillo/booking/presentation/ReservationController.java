package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationDetailResponse;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.docs.ReservationControllerDoc;
import com.sudo.raillo.booking.success.BookingSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController implements ReservationControllerDoc {

	private final ReservationFacade reservationFacade;
	private final ReservationService reservationService;


	/***
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 생성 성공 응답
	 */
	@PostMapping
	public SuccessResponse<ReservationCreateResponse> createReservation(
		@RequestBody @Valid ReservationCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		ReservationCreateResponse response = reservationFacade
			.createReservation(request, userDetails.getUsername());
		return SuccessResponse.of(BookingSuccess.RESERVATION_CREATE_SUCCESS, response);
	}

	/**
	 * 예약 목록 조회 메서드
	 * @return 회원의 예약 목록 응답
	 * */
	@GetMapping
	public SuccessResponse<List<ReservationDetailResponse>> getReservations(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<ReservationDetailResponse> response = reservationService.getReservationDetails(memberNo);
		return SuccessResponse.of(BookingSuccess.RESERVATION_LIST_SUCCESS, response);
	}

	/**
	 * 예약 다중 삭제 메서드
	 * @param request 예약 삭제 요청 DTO
	 */
	@DeleteMapping
	public SuccessResponse<?> deleteReservations(
		@RequestBody @Valid ReservationDeleteRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		reservationFacade.deleteReservations(request.reservationCodes(), memberNo);
		return SuccessResponse.of(BookingSuccess.RESERVATION_DELETE_SUCCESS);
	}

}
