package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.mapper.BookingMapper;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingQueryRepository;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainErrorCode;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.SeatRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BookingService {

	private final MemberRepository memberRepository;
	private final BookingRepository bookingRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final BookingRedisRepository bookingRedisRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final SeatRepository seatRepository;
	private final SeatBookingRepository seatBookingRepository;
	private final BookingMapper bookingMapper;

	/**
	 * 주문으로부터 확정 예약을 생성
	 * @param order 주문
	 * */
	public void createBookingFromOrder(Order order) {
		// 1. 도메인 규칙 검증
		validateOrderForBooking(order);

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

		// 4. Booking, SeatBooking 생성
		orderBookings.forEach(orderBooking -> {
			List<OrderSeatBooking> relatedSeatBookings = seatBookingMap.get(orderBooking.getId());
			createBooking(order.getMember(), orderBooking, relatedSeatBookings, seats);
		});

		log.info("[주문에 대한 확정 예약 생성 완료]: orderId={}, memberNo={}", order.getId(), order.getMember().getId());
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
	private void createBooking(
		Member member,
		OrderBooking orderBooking,
		List<OrderSeatBooking> orderSeatBookings,
		List<Seat> seats
	) {
		Booking booking = Booking.create(
			member,
			orderBooking.getTrainSchedule(),
			orderBooking.getDepartureStop(),
			orderBooking.getArrivalStop()
		);
		bookingRepository.save(booking);

		orderSeatBookings.forEach(orderSeatBooking -> createSeatBooking(booking, orderSeatBooking, seats));
	}

	private void createSeatBooking(Booking booking, OrderSeatBooking orderSeatBooking, List<Seat> seats) {
		Seat seat = seats.stream()
			.filter(s -> s.getId().equals(orderSeatBooking.getSeatId()))
			.findFirst()
			.orElseThrow(() -> new BusinessException(TrainErrorCode.SEAT_NOT_FOUND));

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

	private void validateOrderForBooking(Order order) {
		if (order.getOrderStatus() == OrderStatus.EXPIRED) {
			throw new BusinessException(OrderError.ORDER_IS_EXPIRED);
		}

		if (order.getOrderStatus() != OrderStatus.ORDERED) {
			throw new BusinessException(OrderError.NOT_ORDERED);
		}
	}

}
