package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.mapper.BookingMapper;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingQueryRepository;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

	private final TrainScheduleRepository trainScheduleRepository;
	private final MemberRepository memberRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final BookingRepository bookingRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final BookingRedisRepository bookingRedisRepository;
	private final BookingMapper bookingMapper;

	/**
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 레코드
	 */
	public Booking createBooking(PendingBookingCreateRequest request, String memberNo) {
		Member member = getMember(memberNo);
		TrainSchedule trainSchedule = getTrainSchedule(request.trainScheduleId());
		ScheduleStop departureStop = getStopStation(trainSchedule, request.departureStationId());
		ScheduleStop arrivalStop = getStopStation(trainSchedule, request.arrivalStationId());

		validateTrainOperating(trainSchedule);

		Booking booking = Booking.create(
			member,
			trainSchedule,
			departureStop,
			arrivalStop
		);
		return bookingRepository.save(booking);
	}

	/**
	 * 예약을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param bookingId 예약 ID
	 * @return 예약
	 */
	@Transactional(readOnly = true)
	public BookingDetail getBooking(String memberNo, Long bookingId) {
		Member member = getMember(memberNo);

		List<BookingInfo> bookingInfos = bookingQueryRepository.findBookingDetail(
			member.getId(), List.of(bookingId));

		if (bookingInfos.isEmpty()) {
			throw new BusinessException(BookingError.BOOKING_NOT_FOUND);
		}

		BookingInfo bookingInfo = bookingInfos.get(0);
		return bookingMapper.convertToBookingDetail(bookingInfo);
	}

	/**
	 * 예약 목록을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @return 예약 목록
	 */
	@Transactional(readOnly = true)
	public List<BookingDetail> getBookings(String memberNo) {
		Member member = getMember(memberNo);

		// 예약 조회
		List<BookingInfo> bookingInfos = bookingQueryRepository.findBookingDetail(member.getId());
		return bookingMapper.convertToBookingDetail(bookingInfos);
	}

	/**
	 * PendingBooking 조회
	 */
	@Transactional(readOnly = true)
	public PendingBooking getPendingBooking(String pendingBookingId) {
		return bookingRedisRepository.getPendingBooking(pendingBookingId)
			.orElseThrow(() -> {
				log.info("[임시 예약 조회 실패] pendingBookingId={}", pendingBookingId);
				return new BusinessException(BookingError.PENDING_BOOKING_NOT_FOUND);
			});
	}

	/**
	 * 여러 PendingBooking 한 번에 조회 및 검증
	 * - 모든 예약이 Redis에 존재해야 함
	 */
	@Transactional(readOnly = true)
	public List<PendingBooking> getPendingBookings(List<String> pendingBookingIds) {
		Map<String, PendingBooking> bookingsById = bookingRedisRepository.getPendingBookingsAsMap(pendingBookingIds);

		List<String> notFoundIds = pendingBookingIds.stream()
			.filter(id -> !bookingsById.containsKey(id))
			.toList();

		if (!notFoundIds.isEmpty()) {
			log.warn("[임시 예약 찾지 못함] pendingBookingIds={} - TTL 만료 또는 이미 사용됨", notFoundIds);
			throw new BusinessException(BookingError.PENDING_BOOKING_NOT_FOUND);
		}

		return pendingBookingIds.stream()
			.map(bookingsById::get)
			.toList();
	}

	/**
	 * 특정 예약을 삭제하는 메서드
	 * @param bookingId 삭제할 예약의 ID
	 */
	public void deleteBooking(Long bookingId) {
		bookingRepository.deleteById(bookingId);
	}

	public void deleteAllByMemberId(Long memberId) {
		bookingRepository.deleteAllByMemberId(memberId);
	}

	// private Method
	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}

	private ScheduleStop getStopStation(TrainSchedule trainSchedule, Long request) {
		return scheduleStopRepository.findByTrainScheduleIdAndStationId(trainSchedule.getId(), request)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.STATION_NOT_FOUND));
	}

	private TrainSchedule getTrainSchedule(Long trainScheduleId) {
		return trainScheduleRepository.findById(trainScheduleId)
			.orElseThrow(() -> new BusinessException(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND));
	}

	private static void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainErrorCode.TRAIN_OPERATION_CANCELLED);
		}
	}

	public void validatePendingBookingOwner(PendingBooking pendingBooking, String memberNo) {
		if (!pendingBooking.getMemberNo().equals(memberNo)) {
			log.error("[임시 예약 소유자 불일치] pendingBookingMemberNo={}, requestMemberNo={}",
				pendingBooking.getMemberNo(), memberNo);
			throw new BusinessException(BookingError.PENDING_BOOKING_ACCESS_DENIED);
		}
	}
}
