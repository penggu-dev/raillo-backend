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
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.exception.OrderError;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.payment.domain.Payment;
import com.sudo.raillo.payment.domain.status.PaymentStatus;
import com.sudo.raillo.payment.domain.type.PaymentMethod;
import com.sudo.raillo.payment.exception.PaymentError;
import com.sudo.raillo.payment.infrastructure.PaymentRepository;
import com.sudo.raillo.payment.exception.TossPaymentException;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;
import com.sudo.raillo.payment.infrastructure.dto.TossPaymentConfirmResponse;
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

	/**
	 * REQUIRES_NEW 독립 커밋 검증
	 *
	 * <p>토스 결제 실패로 바깥 트랜잭션이 롤백되어도,
	 * REQUIRES_NEW로 저장한 paymentKey와 실패 정보는 DB에 남아있어야 합니다.</p>
	 */
	@Test
	@DisplayName("토스 결제 실패로 바깥 트랜잭션이 롤백되어도 REQUIRES_NEW로 저장한 paymentKey는 살아있다")
	void confirmPayment_tossFailure_paymentKeySurvivedByRequiresNew() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_requires_new_test";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		// 토스 API 실패 → 바깥 트랜잭션 롤백
		given(tossPaymentClient.confirmPayment(any(PaymentConfirmRequest.class)))
			.willThrow(new TossPaymentException(400, "INVALID_REQUEST", "test error"));

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when - 바깥 트랜잭션 롤백
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
			.isInstanceOf(TossPaymentException.class)
			.hasFieldOrPropertyWithValue("httpStatus", 400)
			.hasFieldOrPropertyWithValue("errorCode", "INVALID_REQUEST")
			.hasMessageContaining("test error");

		// then - REQUIRES_NEW로 커밋한 paymentKey는 롤백과 무관하게 살아있어야 함
		Payment savedPayment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
		assertThat(savedPayment.getPaymentKey())
			.as("REQUIRES_NEW 트랜잭션은 바깥 트랜잭션 롤백과 독립적으로 커밋된다")
			.isEqualTo(paymentKey);
		assertThat(savedPayment.getPaymentStatus())
			.as("failPaymentInNewTransaction도 REQUIRES_NEW로 커밋되어 FAILED 상태가 유지된다")
			.isEqualTo(PaymentStatus.FAILED);

		// 바깥 트랜잭션은 롤백되었으므로 Order는 PENDING 상태 그대로
		Order savedOrder = orderRepository.findByOrderCode(prepareResponse.orderId()).orElseThrow();
		assertThat(savedOrder.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
	}

	// ========== 실패 시나리오 테스트 ==========

	@Test
	@DisplayName("PendingBooking이 TTL 만료되면 PENDING_BOOKING_EXPIRED 예외가 발생한다")
	void confirmPayment_pendingBookingExpired_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_expired_test";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		// PendingBooking을 Redis에서 삭제하여 TTL 만료 시뮬레이션
		bookingRedisRepository.deletePendingBooking(pendingBooking.getId());

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when & then
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", BookingError.PENDING_BOOKING_EXPIRED)
			.hasMessage(BookingError.PENDING_BOOKING_EXPIRED.getMessage());
	}

	@Test
	@DisplayName("Order 소유자가 아닌 회원이 결제 승인을 시도하면 ORDER_ACCESS_DENIED 예외가 발생한다")
	void confirmPayment_orderOwnerMismatch_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_owner_test";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		// 다른 회원 생성
		Member otherMember = memberRepository.save(MemberFixture.createOther());
		String otherMemberNo = otherMember.getMemberDetail().getMemberNo();

		// 다른 회원의 PendingBooking도 만들어서 Redis에 소유자 검증을 통과시킴
		PendingBooking otherPendingBooking = PendingBookingFixture.builder()
			.withId(pendingBooking.getId())
			.withMemberNo(otherMemberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withTotalFare(amount)
			.build();
		bookingRedisRepository.savePendingBooking(otherPendingBooking);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when & then
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, otherMemberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", OrderError.ORDER_ACCESS_DENIED)
			.hasMessage(OrderError.ORDER_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("요청 금액과 Order 금액이 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
	void confirmPayment_amountMismatch_throwsException() {
		// given
		BigDecimal orderAmount = BigDecimal.valueOf(50000);
		BigDecimal wrongRequestAmount = BigDecimal.valueOf(30000);
		String paymentKey = "toss_pk_amount_test";

		PendingBooking pendingBooking = createPendingBookingWithHold(orderAmount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), wrongRequestAmount);

		// when & then
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_AMOUNT_MISMATCH)
			.hasMessage(PaymentError.PAYMENT_AMOUNT_MISMATCH.getMessage());
	}

	@Test
	@DisplayName("이미 PAID 상태의 결제가 존재하면 PAYMENT_ALREADY_COMPLETED 예외가 발생한다")
	void confirmPayment_duplicatePayment_throwsException() {
		// given
		BigDecimal amount = BigDecimal.valueOf(50000);
		String paymentKey = "toss_pk_duplicate_test";

		PendingBooking pendingBooking = createPendingBookingWithHold(amount);
		PaymentPrepareResponse prepareResponse = paymentFacade.preparePayment(
			new PaymentPrepareRequest(List.of(pendingBooking.getId())), memberNo);

		// 기존 Payment를 PAID 상태로 변경
		Order order = orderRepository.findByOrderCode(prepareResponse.orderId()).orElseThrow();
		Payment existingPayment = paymentRepository.findByOrder(order).orElseThrow();
		existingPayment.approve(PaymentMethod.CREDIT_CARD);
		paymentRepository.saveAndFlush(existingPayment);

		PaymentConfirmRequest confirmRequest = new PaymentConfirmRequest(
			paymentKey, prepareResponse.orderId(), amount);

		// when & then
		assertThatThrownBy(() -> paymentFacade.confirmPayment(confirmRequest, memberNo))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", PaymentError.PAYMENT_ALREADY_COMPLETED)
			.hasMessage(PaymentError.PAYMENT_ALREADY_COMPLETED.getMessage());
	}

	private PendingBooking createPendingBookingWithHold(BigDecimal fare) {
		ScheduleStop departureStop = trainScheduleResult.scheduleStops().get(0);
		ScheduleStop arrivalStop = trainScheduleResult.scheduleStops().get(1);

		List<Seat> seats = trainTestHelper.getSeats(
			trainScheduleResult.trainSchedule().getTrain(), CarType.STANDARD, 1);
		List<Long> seatIds = seats.stream().map(Seat::getId).toList();
		Long trainCarId = seats.get(0).getTrainCar().getId();

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
			seatIds,
			trainCarId
		);

		bookingRedisRepository.savePendingBooking(pendingBooking);
		return pendingBooking;
	}
}
