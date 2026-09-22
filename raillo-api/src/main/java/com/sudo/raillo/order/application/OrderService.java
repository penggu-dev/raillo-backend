package com.sudo.raillo.order.application;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.global.redis.util.RedisJsonConverter;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.application.dto.OrderBookingInfo;
import com.sudo.raillo.order.application.dto.OrderSeatBookingInfo;
import com.sudo.raillo.order.application.validator.OrderValidator;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.order.domain.OrderSeatBooking;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderBookingRepository;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.order.infrastructure.OrderSeatBookingRepository;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.exception.TrainError;
import com.sudo.raillo.train.infrastructure.ScheduleStopRepository;
import com.sudo.raillo.train.infrastructure.TrainScheduleRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

	private final OrderRepository orderRepository;
	private final OrderBookingRepository orderBookingRepository;
	private final OrderSeatBookingRepository orderSeatBookingRepository;
	private final TrainScheduleRepository trainScheduleRepository;
	private final ScheduleStopRepository scheduleStopRepository;
	private final MemberRepository memberRepository;
	private final RedisJsonConverter jsonConverter;
	private final OrderValidator orderValidator;

	/**
	 * OrderCode로 Order 조회
	 */
	@Transactional(readOnly = true)
	public Order getOrderByOrderCode(String orderCode) {
		return orderRepository.findByOrderCode(orderCode)
			.orElseThrow(() -> new BusinessException(OrderError.ORDER_NOT_FOUND));
	}

	/**
	 * 주문 소유자 검증
	 */
	@Transactional(readOnly = true)
	public void validateOrderOwner(Order order, Member member) {
		if (!order.getMember().getId().equals(member.getId())) {
			log.error("[소유자 불일치] Order의 소유자가 아님: orderCode={}, requestMemberId={}, orderMemberId={}",
				order.getOrderCode(), member.getId(), order.getMember().getId());
			throw new BusinessException(OrderError.ORDER_ACCESS_DENIED);
		}
	}

	/**
	 * Order에 연결된 Reservation ID 목록 조회
	 */
	@Transactional(readOnly = true)
	public List<String> getReservationIds(Order order) {
		return orderBookingRepository.findByOrderId(order.getId()).stream()
			.map(OrderBooking::getReservationId)
			.toList();
	}

	/**
	 * 주문 생성
	 * @param memberNo 회원 번호
	 * @param reservations 주문할 Reservation 리스트
	 * @return 생성된 Order
	 */
	public Order createOrder(String memberNo, List<Reservation> reservations) {
		orderValidator.validateReservationsNotEmpty(reservations);

		Member member = getMember(memberNo);

		// 1. 연관 엔티티 일괄 조회 (N+1 방지)
		Map<Long, TrainSchedule> scheduleMap = getScheduleMap(reservations);
		Map<Long, ScheduleStop> stopMap = getStopMap(reservations);

		// 2. 운임 계산 정보 생성
		List<OrderBookingInfo> orderBookingInfos = createOrderBookingInfos(reservations);

		// 3. 총 주문 금액 계산
		BigDecimal totalAmount = orderBookingInfos.stream()
			.map(OrderBookingInfo::totalFare)
			.reduce(BigDecimal.ZERO, BigDecimal::add);

		// 4. Order 생성 및 저장
		Order order = Order.create(member, totalAmount);
		orderRepository.save(order);

		// 5. OrderBooking, OrderSeatBooking 생성
		for (int i = 0; i < reservations.size(); i++) {
			createOrderBooking(order, orderBookingInfos.get(i), scheduleMap, stopMap, reservations.get(i));
		}
		log.info("[주문 생성] orderId={}, memberNo={}, totalAmount={}", order.getId(), memberNo, totalAmount);
		return order;
	}

	private void createOrderBooking(
		Order order,
		OrderBookingInfo orderBookingInfo,
		Map<Long, TrainSchedule> scheduleMap,
		Map<Long, ScheduleStop> stopMap,
		Reservation reservation
	) {
		OrderBooking orderBooking = OrderBooking.create(
			orderBookingInfo.reservationId(),
			order,
			scheduleMap.get(orderBookingInfo.trainScheduleId()),
			stopMap.get(orderBookingInfo.departureStopId()),
			stopMap.get(orderBookingInfo.arrivalStopId()),
			orderBookingInfo.totalFare()
		);
		orderBooking.captureReservation(jsonConverter.toJson(reservation));
		orderBookingRepository.save(orderBooking);
		createOrderSeatBookings(orderBookingInfo, orderBooking);
	}

	private void createOrderSeatBookings(OrderBookingInfo orderBookingInfo, OrderBooking orderBooking) {
		orderBookingInfo.seatInfos().stream()
			.map(seatInfo -> OrderSeatBooking.create(
				orderBooking,
				seatInfo.seatId(),
				seatInfo.passengerType(),
				seatInfo.fare()
			)).forEach(orderSeatBookingRepository::save);
	}

	private Map<Long, TrainSchedule> getScheduleMap(List<Reservation> reservations) {
		Set<Long> scheduleIds = reservations.stream()
			.map(Reservation::trainScheduleId)
			.collect(Collectors.toSet());

		List<TrainSchedule> schedules = trainScheduleRepository.findAllByIdWithTrain(scheduleIds);

		if (schedules.size() != scheduleIds.size()) {
			throw new BusinessException(TrainError.TRAIN_SCHEDULE_DETAIL_NOT_FOUND);
		}

		return schedules.stream().collect(Collectors.toMap(TrainSchedule::getId, schedule -> schedule));
	}

	private Map<Long, ScheduleStop> getStopMap(List<Reservation> reservations) {
		Set<Long> stopIds = reservations.stream()
			.flatMap(pb -> java.util.stream.Stream.of(pb.departure().stopId(), pb.arrival().stopId()))
			.collect(Collectors.toSet());

		List<ScheduleStop> stops = scheduleStopRepository.findAllByIdWithStation(stopIds);

		if (stops.size() != stopIds.size()) {
			throw new BusinessException(TrainError.STATION_NOT_FOUND);
		}

		return stops.stream().collect(Collectors.toMap(ScheduleStop::getId, scheduleStop -> scheduleStop));
	}

	private List<OrderBookingInfo> createOrderBookingInfos(List<Reservation> reservations) {
		return reservations.stream().map(reservation -> new OrderBookingInfo(
			reservation.reservationId(), reservation.trainScheduleId(), reservation.departure().stopId(),
			reservation.arrival().stopId(), reservation.totalFare(),
			reservation.seats().stream().map(seat -> new OrderSeatBookingInfo(
				seat.seatId(), seat.passengerType(), seat.fare())).toList()
		)).toList();
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}
}
