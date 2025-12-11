package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.request.BookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.mapper.BookingMapper;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingQueryRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

	private final TrainScheduleRepository trainScheduleRepository;
	private final MemberRepository memberRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final BookingRepository bookingRepository;
	private final SeatRepository seatRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final BookingMapper bookingMapper;

	/**
	 * 예약을 생성하는 메서드
	 * @param request 예약 생성 요청 DTO
	 * @return 예약 레코드
	 */
	public Booking createBooking(BookingCreateRequest request, String memberNo) {
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
	 * 객차 타입 조회
	 */
	public CarType findCarType(List<Long> seatIds) {
		if (seatIds.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		List<CarType> carTypes = seatRepository.findCarTypes(seatIds);

		if (carTypes.isEmpty()) {
			throw new BusinessException(BookingError.SEAT_NOT_FOUND);
		}

		if (carTypes.size() != 1) {
			throw new BusinessException(BookingError.INVALID_CAR_TYPE);
		}
		return carTypes.get(0);
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
}
