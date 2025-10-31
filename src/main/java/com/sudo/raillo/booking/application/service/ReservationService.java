package com.sudo.raillo.booking.application.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.application.generator.ReservationCodeGenerator;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.config.BookingConfig;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepositoryCustom;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

	private final BookingConfig bookingConfig;
	private final TrainScheduleRepository trainScheduleRepository;
	private final MemberRepository memberRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationRepositoryCustom reservationRepositoryCustom;
	private final ReservationCodeGenerator reservationCodeGenerator;
	private final ReservationMapper reservationMapper;

	/**
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 레코드
	 */
	public Reservation createReservation(ReservationCreateRequest request, String memberNo, BigDecimal totalFare) {
		TrainSchedule trainSchedule = getTrainSchedule(request);
		Member member = memberRepository.getMember(memberNo);
		ScheduleStop departureStop = getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = getStopStation(trainSchedule, request.arrivalStationId());

		validateTrainOperating(trainSchedule);

		Reservation reservation = generateReservation(
			request, trainSchedule, member, departureStop, arrivalStop, totalFare
		);
		return reservationRepository.save(reservation);
	}

	/**
	 * 특정 예약을 삭제하는 메서드 - 단수 예약 ID 사용
	 * @param reservationId 삭제할 예약의 ID
	 */
	public void deleteReservation(Long reservationId) {
		reservationRepository.deleteById(reservationId);
	}

	/**
	 * 다수의 예약을 삭제하는 메서드 - 복수 예약 ID 사용
	 * @param reservationIds 삭제할 예약의 ID를 원소로 하는 리스트
	 */
	public void deleteReservation(List<Long> reservationIds) {
		reservationRepository.deleteAllByIdInBatch(reservationIds);
	}

	/**
	 * 만료된 예약을 일괄삭제하는 메서드
	 */
	public void expireReservations() {
		LocalDateTime now = LocalDateTime.now();
		int pageNumber = 0;
		final int pageSize = 500;
		Page<Reservation> expiredPage;
		do {
			Pageable pageable = PageRequest.of(pageNumber, pageSize);
			expiredPage = reservationRepository
				.findAllByExpiresAtBeforeAndReservationStatus(now, ReservationStatus.RESERVED, pageable);
			if (expiredPage.hasContent()) {
				List<Long> expiredList = expiredPage.getContent()
					.stream()
					.map(Reservation::getId)
					.toList();
				reservationRepository.deleteAllByIdInBatch(expiredList);
			}
			pageNumber++;
		} while (expiredPage.hasNext());
	}

	public void deleteAllByMemberId(Long memberId) {
		reservationRepository.deleteAllByMemberId(memberId);
	}

	/**
	 * 특정 회원의 특정 예약 상세 정보를 조회하는 메서드
	 * @param memberId 회원 ID
	 * @param reservationIds 예약 ID 목록
	 * @return 예약 상세 정보 목록
	 */
	@Transactional(readOnly = true)
	public List<ReservationInfo> findReservationDetail(Long memberId, List<Long> reservationIds) {
		return reservationRepositoryCustom.findReservationDetail(memberId, reservationIds);
	}

	/**
	 * 특정 회원의 모든 예약 상세 정보를 조회하는 메서드
	 * @param memberId 회원 ID
	 * @return 예약 상세 정보 목록
	 */
	@Transactional(readOnly = true)
	public List<ReservationInfo> findReservationDetail(Long memberId) {
		return reservationRepositoryCustom.findReservationDetail(memberId);
	}

	private ScheduleStop getStopStation(TrainSchedule trainSchedule, Long request) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), request)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(ReservationCreateRequest request) {
		return trainScheduleRepository.findById(request.trainScheduleId())
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	private static void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainErrorCode.TRAIN_OPERATION_CANCELLED);
		}
	}

	private Reservation generateReservation(
		ReservationCreateRequest request,
		TrainSchedule trainSchedule,
		Member member,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		BigDecimal totalFare
	) {
		return Reservation.builder()
			.trainSchedule(trainSchedule)
			.member(member)
			.reservationCode(reservationCodeGenerator.generateReservationCode())
			.tripType(request.tripType())
			.totalPassengers(request.passengers().stream().mapToInt(PassengerSummary::getCount).sum())
			.passengerSummary(reservationMapper.convertPassengersToJson(request))
			.reservationStatus(ReservationStatus.RESERVED)
			.expiresAt(LocalDateTime.now().plusMinutes(bookingConfig.getExpiration().getReservation()))
			.fare(totalFare.intValue())
			.departureStop(departureStop)
			.arrivalStop(arrivalStop)
			.build();
	}
}
