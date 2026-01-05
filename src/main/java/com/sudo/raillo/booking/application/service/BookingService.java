package com.sudo.raillo.booking.application.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.mapper.BookingMapper;
import com.sudo.raillo.booking.application.validator.BookingValidator;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingQueryRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.SeatRepository;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

	private final MemberRepository memberRepository;
	private final BookingRepository bookingRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final SeatRepository seatRepository;
	private final SeatBookingRepository seatBookingRepository;
	private final BookingMapper bookingMapper;
	private final BookingValidator bookingValidator;

	/**
	 * 주문으로부터 확정 예약을 생성
	 * @param order 주문
	 * */
	public void createBookingFromOrder(Order order) {
		// 1. 도메인 규칙 검증
		order.validateCompleted();

		// 2. 관련 OrderBooking, OrderSeatBooking 조회
		List<OrderBooking> orderBookings = getOrderBookings(order.getId());
		List<OrderSeatBooking> orderSeatBookings = getOrderSeatBookings(
			orderBookings.stream()
				.map(OrderBooking::getId)
				.toList()
		);

		// OrderBooking ID로 미리 그룹핑
		Map<Long, List<OrderSeatBooking>> seatBookingMap = orderSeatBookings.stream()
			.collect(Collectors.groupingBy(osb -> osb.getOrderBooking().getId()));

		// 3. OrderSeatBooking seatId로 좌석 조회
		List<Seat> seats = getSeats(
			orderSeatBookings.stream()
				.map(OrderSeatBooking::getSeatId)
				.toList()
		);

		// Seat List -> Map 변환
		Map<Long, Seat> seatMap = seats.stream()
			.collect(Collectors.toMap(Seat::getId, Function.identity()));

		// 4. Booking, SeatBooking 생성
		orderBookings.forEach(orderBooking -> {
			List<OrderSeatBooking> relatedSeatBookings = seatBookingMap.get(orderBooking.getId());
			createBooking(order.getMember(), orderBooking, relatedSeatBookings, seatMap);
		});

		log.info("[주문에 대한 확정 예약 생성 완료]: orderId={}, memberNo={}", order.getId(), order.getMember().getId());
	}

	/***
	 * 새로운 좌석 예약 현황을 생성하고 예약하는 메서드 (redis 변경 전 좌석 락 참고용)
	 * @param booking Booking Entity
	 * @param seat Seat Entity
	 * @return SeatBooking Entity
	 */
	public SeatBooking reserveNewSeat(Booking booking, Seat seat, PassengerType passengerType) {
		try {
			Long trainScheduleId = booking.getTrainSchedule().getId();
			Long seatId = seat.getId();

			// 1. 먼저 좌석 자체에 비관적 락을 걸어 동시 접근 차단 (최우선 락)
			Seat lockedSeat = seatRepository.findByIdWithLock(seatId)
				.orElseThrow(() -> new BusinessException(BookingError.SEAT_NOT_FOUND));

			// 2. 락이 걸린 상태에서 해당 좌석의 기존 예약들을 비관적 락으로 조회
			List<SeatBooking> existingBookings = seatBookingRepository
				.findByTrainScheduleAndSeatWithLock(trainScheduleId, seatId);

			// 3. 락이 걸린 상태에서 충돌 검증 (원자성 보장)
			bookingValidator.validateConflictWithExistingBookings(booking, existingBookings);

			SeatBooking seatBooking = SeatBooking.create(
				booking.getTrainSchedule(),
				lockedSeat,
				booking,
				passengerType
			);
			return seatBookingRepository.save(seatBooking);
		} catch (OptimisticLockException | DataIntegrityViolationException e) {
			// 동시성 문제 및 유니크 제약 위반 발생
			throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
		}
	}

	/**
	 * 예약을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param bookingId 예약 ID
	 * @return 예약
	 */
	@Transactional(readOnly = true)
	public BookingResponse getBooking(String memberNo, Long bookingId) {
		Member member = getMember(memberNo);

		List<BookingInfo> bookingInfos = bookingQueryRepository.findBookings(
			member.getId(), List.of(bookingId));

		if (bookingInfos.isEmpty()) {
			throw new BusinessException(BookingError.BOOKING_NOT_FOUND);
		}

		BookingInfo bookingInfo = bookingInfos.get(0);
		return bookingMapper.convertToBookingDetail(bookingInfo);
	}

	/**
	 * 승차권 목록 조회 (bookingId로 단위)
	 * @param memberNo 회원 번호
	 * @param timeFilter 시간 필터 (UPCOMING: 승차권 조회, HISTORY: 구입 이력, ALL: 전체)
	 * @return 승차권 목록
	 */
	@Transactional(readOnly = true)
	public List<BookingResponse> getBookings(String memberNo, BookingTimeFilter timeFilter) {
		Member member = getMember(memberNo);

		// 예약 조회
		List<BookingInfo> bookingInfos = bookingQueryRepository.findBookings(member.getId(), timeFilter);
		return bookingMapper.convertToBookingDetail(bookingInfos);
	}

	/**
	 * 특정 확정 예약을 삭제하는 메서드
	 * @param bookingId 삭제할 확정 예약의 ID
	 */
	public void deleteBooking(Long bookingId) {
		bookingRepository.deleteById(bookingId);
	}

	/**
	 * 확정 예약과 연관된 확정 좌석 예약을 삭제하는 메서드
	 * @param seatBookingId 삭제할 확정 좌석 예약의 ID
	 */
	public void deleteSeatBooking(Long seatBookingId) {
		SeatBooking seatBooking = seatBookingRepository.findById(seatBookingId)
			.orElseThrow(() -> new BusinessException(BookingError.SEAT_BOOKING_NOT_FOUND));
		seatBookingRepository.delete(seatBooking);
	}

	/**
	 * 확정 예약과 연관된 좌석 예약을 모두 삭제하는 메서드
	 * @param bookingId 연관된 확정 예약의 ID
	 */
	public void deleteSeatBookingByBookingId(Long bookingId) {
		seatBookingRepository.deleteAllByBookingId(bookingId);
	}

	// private Method
	private void createBooking(
		Member member,
		OrderBooking orderBooking,
		List<OrderSeatBooking> orderSeatBookings,
		Map<Long, Seat> seatMap
	) {
		Booking booking = Booking.create(
			member,
			orderBooking.getTrainSchedule(),
			orderBooking.getDepartureStop(),
			orderBooking.getArrivalStop()
		);
		bookingRepository.save(booking);

		orderSeatBookings.forEach(orderSeatBooking -> createSeatBooking(booking, orderSeatBooking, seatMap));
	}

	private void createSeatBooking(
		Booking booking,
		OrderSeatBooking orderSeatBooking,
		Map<Long, Seat> seatMap
	) {
		Seat seat = seatMap.get(orderSeatBooking.getSeatId());
		if (seat == null) {
			throw new BusinessException(TrainErrorCode.SEAT_NOT_FOUND);
		}

		SeatBooking seatBooking = SeatBooking.create(
			booking.getTrainSchedule(),
			seat,
			booking,
			orderSeatBooking.getPassengerType()
		);
		seatBookingRepository.save(seatBooking);
	}

	private List<OrderBooking> getOrderBookings(Long orderId) {
		List<OrderBooking> orderBookings = orderBookingRepository.findByOrderId(orderId);
		if (orderBookings.isEmpty()) {
			throw new BusinessException(OrderError.ORDER_BOOKING_NOT_FOUND);
		}

		return orderBookings;
	}

	private List<OrderSeatBooking> getOrderSeatBookings(List<Long> orderBookingIds) {
		List<OrderSeatBooking> orderSeatBookings = orderSeatBookingRepository.findByOrderBookingIds(orderBookingIds);
		if (orderSeatBookings.isEmpty()) {
			throw new BusinessException(OrderError.ORDER_SEAT_BOOKING_NOT_FOUND);
		}

		return orderSeatBookings;
	}

	private List<Seat> getSeats(List<Long> seatIds) {
		return seatRepository.findAllById(seatIds);
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}

}
