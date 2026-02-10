package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sudo.raillo.booking.application.service.SeatHoldService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

@ServiceTest
class PaymentFacadeConfirmTest {

	@Autowired
	private PaymentFacade paymentFacade;

	@MockitoBean
	private TossPaymentClient tossPaymentClient;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private SeatHoldService seatHoldService;

	private Member member;
	private String memberNo;
	private TrainScheduleResult trainScheduleResult;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.create());
		memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
	}

	/**
	 * 결제 승인 성공 시 paymentKey가 DB에 정상 저장되는지 검증
	 *
	 * <p>이 테스트는 REQUIRES_NEW 트랜잭션으로 저장한 paymentKey가
	 * 바깥 트랜잭션 커밋 시 Hibernate의 전체 컬럼 UPDATE로 인해
	 * null로 덮어쓰이는 버그를 방지합니다.</p>
	 */
	@Test
	@DisplayName("결제 승인 성공 시 paymentKey가 DB에 정상 저장된다")
	void confirmPayment_paymentKeyPersistedInDatabase() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_test_12345";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, prepareResponse.orderId(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willReturn(tossResponse);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when
		PaymentConfirmResponse response = paymentFacade.confirmPayment(confirmRequest, memberNo);

		// then - DB에서 직접 조회하여 paymentKey가 null이 아닌지 검증
		Payment savedPayment = paymentRepository.findById(response.paymentId()).orElseThrow();
		assertThat(savedPayment.getPaymentKey())
			.as("REQUIRES_NEW 트랜잭션으로 저장한 paymentKey가 바깥 트랜잭션 커밋 시 덮어쓰이면 안 된다")
			.isEqualTo(paymentKey);
	}

	@Test
	@DisplayName("결제 승인 성공 시 Payment 상태가 PAID로 변경된다")
	void confirmPayment_paymentStatusChangedToPaid() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_test_67890";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse(
			paymentKey, prepareResponse.orderId(), "카드", amount.longValue(), "DONE");
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willReturn(tossResponse);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when
		PaymentConfirmResponse response = paymentFacade.confirmPayment(confirmRequest, memberNo);

		// then
		Payment savedPayment = paymentRepository.findById(response.paymentId()).orElseThrow();
		assertThat(savedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
		assertThat(savedPayment.getPaidAt()).isNotNull();

		Order savedOrder = orderRepository.findByOrderCode(prepareResponse.orderId()).orElseThrow();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.ORDERED);
	}

	private PendingBooking createPendingBookingWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Long> seatIds = trainTestHelper.getSeatIds(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 1);

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(List.of(
				new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)
			))
			.withTotalFare(fare)
			.build();

		// 실제 플로우처럼 좌석 Hold 먼저 설정 (PendingBookingFacade가 하는 일)
		seatHoldService.holdSeats(
			pendingBooking.getId(),
			trainScheduleResult.trainSchedule().getId(),
			departureStop,
			arrivalStop,
			seatIds
		);

		bookingRedisRepository.savePendingBooking(pendingBooking);
		return pendingBooking;
	}
}
