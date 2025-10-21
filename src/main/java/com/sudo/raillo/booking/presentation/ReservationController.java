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

import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.service.ReservationDeletionService;
import com.sudo.raillo.booking.application.service.ReservationQueryService;
import com.sudo.raillo.booking.docs.ReservationControllerDocs;
import com.sudo.raillo.booking.success.ReservationSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/booking/reservation")
@RequiredArgsConstructor
public class ReservationController implements ReservationControllerDocs {

	private final ReservationFacade reservationFacade;
	private final ReservationService reservationService;
	private final ReservationDeletionService reservationDeletionService;
	private final ReservationQueryService reservationQueryService;

	/***
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 생성 성공 응답
	 */
	@PostMapping
	public SuccessResponse<ReservationCreateResponse> createReservation(
		@RequestBody ReservationCreateRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		ReservationCreateResponse response = reservationFacade
			.createReservation(request, userDetails.getUsername());
		return SuccessResponse.of(ReservationSuccess.RESERVATION_CREATE_SUCCESS, response);
	}

	/***
	 * 예약을 삭제하는 메서드
	 * @param request 예약 삭제 요청 DTO
	 * @return 예약 삭제 성공 응답
	 */
	@DeleteMapping
	public SuccessResponse<?> deleteReservation(@RequestBody ReservationDeleteRequest request) {
		reservationDeletionService.deleteReservation(request.reservationId());
		return SuccessResponse.of(ReservationSuccess.RESERVATION_DELETE_SUCCESS);
	}

	/**
	 * 예약을 조회하는 메서드
	 */
	@GetMapping("/{reservationId}")
	public SuccessResponse<ReservationDetail> getReservation(
		@PathVariable Long reservationId,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		ReservationDetail detail = reservationQueryService.getReservation(memberNo, reservationId);
		return SuccessResponse.of(ReservationSuccess.RESERVATION_DETAIL_SUCCESS, detail);
	}

	/**
	 * 예약 목록을 조회하는 메서드
	 */
	@GetMapping
	public SuccessResponse<List<ReservationDetail>> getReservations(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();

		List<ReservationDetail> response = reservationQueryService.getReservations(memberNo);
		return SuccessResponse.of(ReservationSuccess.RESERVATION_LIST_SUCCESS, response);
	}
}
