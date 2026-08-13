package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.support.helper.ReservationTestHelper;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class PaymentFacadePrepareTest {

	@Autowired
	private ReservationTestHelper reservationTestHelper;

	@Autowired
	private PaymentFacade paymentFacade;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member member;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());

		Train train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	@Test
	@DisplayName("결제 준비 시 Payment가 정상적으로 생성된다")
	void preparePayment_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		Reservation pendingBooking = reservationTestHelper.hold(
			memberNo,
			trainScheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(0)),
			List.of(PassengerType.ADULT));

		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(pendingBooking.getReservationCode()));

		// when
		PaymentPrepareResponse response = paymentFacade.preparePayment(request, memberNo);

		// then
		Order order = orderRepository.findByOrderCode(response.orderId()).get();
		assertThat(response.orderId()).isEqualTo(order.getOrderCode());
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.amount()).isEqualByComparingTo(pendingBooking.getTotalFare());
	}

	@Test
	@DisplayName("여러 좌석이 포함된 여러 PendingBooking으로 결제 준비 시 금액이 합산된다")
	void preparePayment_multiplePendingBookingsWithMultipleSeats_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		// 좌석이 더 많은 열차와 스케줄 생성
		Train train = trainTestHelper.createSmallTestTrain();
		TrainScheduleResult scheduleResult = trainScheduleTestHelper.createDefault(train);

		ScheduleStop departureStop = scheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = scheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 4);

		// 첫 번째 Reservation: 2명 (성인 + 어린이)
		Reservation pendingBooking1 = reservationTestHelper.hold(
			memberNo,
			scheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(0), seats.get(1)),
			List.of(PassengerType.ADULT, PassengerType.CHILD));

		// 두 번째 Reservation: 2명 (성인 + 경로)
		Reservation pendingBooking2 = reservationTestHelper.hold(
			memberNo,
			scheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(2), seats.get(3)),
			List.of(PassengerType.ADULT, PassengerType.SENIOR));

		PaymentPrepareRequest request = new PaymentPrepareRequest(
			List.of(pendingBooking1.getReservationCode(), pendingBooking2.getReservationCode())
		);

		// when
		PaymentPrepareResponse response = paymentFacade.preparePayment(request, memberNo);

		// then
		// 실제 운임 계산: 성인(50000×1.0) + 어린이(50000×0.6) + 성인(50000×1.0) + 경로(50000×0.7) = 165,000원
		BigDecimal expectedAmount = BigDecimal.valueOf(50000 + 30000 + 50000 + 35000);
		Order order = orderRepository.findByOrderCode(response.orderId()).get();
		assertThat(response.orderId()).isEqualTo(order.getOrderCode());
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.amount()).isEqualByComparingTo(expectedAmount);
	}

	@Test
	@DisplayName("존재하지 않는 Reservation ID로 결제 준비 시 예외가 발생한다")
	void preparePayment_pendingBookingNotFound_throwsException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();
		String nonExistentId = UUID.randomUUID().toString();

		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(nonExistentId));

		// when & then
		assertThatThrownBy(() -> paymentFacade.preparePayment(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.RESERVATION_EXPIRED)
			.hasMessage(BookingError.RESERVATION_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("다른 사용자의 PendingBooking으로 결제 준비 시 예외가 발생한다")
	void preparePayment_accessDenied_throwsException() {
		// given
		Member otherMember = memberRepository.save(MemberFixture.createOther());
		String otherMemberNo = otherMember.getMemberDetail().getMemberNo();

		String currentMemberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		// 다른 사용자의 Reservation 생성
		Reservation othersPendingBooking = reservationTestHelper.hold(
			otherMemberNo,
			trainScheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(0)),
			List.of(PassengerType.ADULT));

		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(othersPendingBooking.getReservationCode()));

		// when & then (현재 사용자가 다른 사용자의 PendingBooking으로 결제 시도)
		assertThatThrownBy(() -> paymentFacade.preparePayment(request, currentMemberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_ACCESS_DENIED)
			.hasMessage(BookingError.PENDING_BOOKING_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("회원 탈퇴 후 결제 시도 시 USER_NOT_FOUND 예외가 발생한다")
	void preparePayment_memberDeleted_throwsException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		// 유효한 회원의 Reservation 생성
		Reservation pendingBooking = reservationTestHelper.hold(
			memberNo,
			trainScheduleResult.trainSchedule(),
			departureStop,
			arrivalStop,
			List.of(seats.get(0)),
			List.of(PassengerType.ADULT));

		// 회원 탈퇴
		memberRepository.delete(member);

		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(pendingBooking.getReservationCode()));

		// when & then (탈퇴한 회원의 토큰으로 결제 시도)
		assertThatThrownBy(() -> paymentFacade.preparePayment(request, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", MemberError.USER_NOT_FOUND)
			.hasMessage(MemberError.USER_NOT_FOUND.getMessage());
	}
}
