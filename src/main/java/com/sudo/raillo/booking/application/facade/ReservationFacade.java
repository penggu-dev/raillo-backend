package com.sudo.raillo.booking.application.facade;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.dto.response.ProvisionalBookingResponse;
import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.service.FareCalculationService;
import com.sudo.raillo.booking.application.service.RedisBookingService;
import com.sudo.raillo.booking.application.service.ReservationService;
import com.sudo.raillo.booking.application.service.SeatReservationService;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.application.validator.ReservationValidator;
import com.sudo.raillo.booking.domain.ProvisionalBooking;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReservationFacade {

	private final RedisBookingService redisBookingService;
	private final ReservationService reservationService;
	private final SeatReservationService seatReservationService;
	private final TicketService ticketService;
	private final FareCalculationService fareCalculationService;
	private final ReservationValidator reservationValidator;

	public ProvisionalBookingResponse createProvisionalBooking(ReservationCreateRequest request, String memberNo) {
		// TODO: 요청 파라미터를 여기서 모두 검증할지, 각 서비스에서 검증할지 결정 필요


		// 1. 승객 정보, 좌석 정보 정렬 (승객 정보는 PassengerType에 정의한 순서대로, 좌석 정보는 오름차순)
		List<PassengerSummary> passengers = new ArrayList<>(request.passengers());
		passengers.sort(Comparator.comparingInt(ps -> ps.getPassengerType().ordinal()));
		List<Long> seatIds = new ArrayList<>(request.seatIds());
		seatIds.sort(Comparator.naturalOrder());
		reservationValidator.validatePassengerSeatCount(passengers, seatIds);

		// 2. 역 순서 검증
		reservationValidator.validateStopSequence(
			request.trainScheduleId(),
			request.departureStationId(),
			request.arrivalStationId()
		);

		// 3. 객차 타입 조회
		CarType carType = reservationService.findCarType(request.seatIds());

		// 4. 요금 계산
		BigDecimal totalFare = fareCalculationService.calculateFare(
			request.departureStationId(),
			request.arrivalStationId(),
			request.passengers(),
			carType
		);

		// 5. Redis에 임시 예약 생성
		String bookingId = redisBookingService.createProvisionalBooking(
			memberNo,
			request,
			totalFare.intValue()
		);

		// List<Long> seatReservationIds = seatReservationService.createSeatReservations(reservation, passengers, seatIds);

		return ProvisionalBookingResponse.builder()
			.bookingId(bookingId)
			.expiresAt(LocalDateTime.now().plusMinutes(10))
			.totalFare(totalFare.intValue())
			.build();
	}

	/**
	 * 결제 완료 후 예약 확정 (Redis → DB)
	 */
	@Transactional
	public ReservationCreateResponse confirmBookingByPayment(
		String memberNo,
		String bookingId,
		Long paymentId
	) {
		// 1. Redis에서 임시 예약 조회
		ProvisionalBooking provisional = getProvisionalBooking(bookingId);

		log.info("예약 확정 시작 - bookingId: {}, paymentId: {}", bookingId, paymentId);

		// 2. Reservation 엔티티 생성 (상태: PAID)
		Reservation reservation = reservationService.createConfirmedReservation(
			provisional,
			memberNo,
			paymentId
		);

		// 3. 정차역 검증
		reservationValidator.validateStopSequence(reservation);

		// 4. SeatReservation 생성
		List<PassengerSummary> passengers = new ArrayList<>(provisional.getPassengers());
		passengers.sort(Comparator.comparingInt(ps -> ps.getPassengerType().ordinal()));
		List<Long> seatIds = new ArrayList<>(provisional.getSeatIds());
		seatIds.sort(Comparator.naturalOrder());

		List<Long> seatReservationIds = seatReservationService.createSeatReservations(
			reservation,
			passengers,
			seatIds
		);

		// 5. Redis 임시 데이터 삭제
		redisBookingService.deleteProvisionalBooking(bookingId);

		log.info("예약 확정 완료 - reservationId: {}, status: PAID", reservation.getId());

		return new ReservationCreateResponse(reservation.getId(), seatReservationIds);
	}

	/**
	 * 임시 예약 취소
	 */
	public void cancelProvisionalBooking(String bookingId) {
		log.info("임시 예약 취소 - bookingId: {}", bookingId);
		redisBookingService.deleteProvisionalBooking(bookingId);
	}

	/**
	 * 확정 예약 취소
	 */
	@Transactional
	public void cancelReservation(Long reservationId, String memberNo) {
		Reservation reservation = reservationService.findReservation(reservationId, memberNo);

		// 취소 가능 시간 검증 등 추가 검증 필요

		// 상태 변경: PAID → CANCELLED
		reservation.cancel();

		log.info("예약 취소 완료 - reservationId: {}, status: CANCELLED", reservationId);

		// SeatReservation, Ticket 삭제는 기존 로직 활용
		cancelReservation(reservation);
	}

	/**
	 * 환불 처리
	 */
	@Transactional
	public void completeRefund(Long reservationId, String memberNo) {
		// TODO: reservation Member랑 요청한 멤버랑 ID 일치 비교
		Reservation reservation = reservationService.findReservation(reservationId, memberNo);

		// 상태 변경: CANCELLED → REFUNDED
		reservation.refund();

		log.info("환불 처리 완료 - reservationId: {}, status: REFUNDED", reservationId);
	}

	/**
	 * 임시 예약 조회
	 */
	public ProvisionalBooking getProvisionalBooking(String bookingId) {
		return redisBookingService.getProvisionalBooking(bookingId)
			.orElseThrow(() -> new BusinessException(BookingError.RESERVATION_EXPIRED));
	}

	public void cancelReservation(Reservation reservation) {
		Long reservationId = reservation.getId();
		seatReservationService.deleteSeatReservationByReservationId(reservationId);
		ticketService.deleteTicketByReservationId(reservationId);
	}

	public void deleteReservationsByMember(Member member) {
		reservationService.deleteAllByMemberId(member.getId());
	}
}
