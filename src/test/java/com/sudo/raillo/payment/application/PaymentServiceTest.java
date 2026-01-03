package com.sudo.raillo.payment.application;

import com.sudo.raillo.support.annotation.ServiceTest;
import lombok.extern.slf4j.Slf4j;

@ServiceTest
@Slf4j
class PaymentServiceTest {

// 	@Autowired
// 	private PaymentService paymentService;
//
// 	@Autowired
// 	private MemberRepository memberRepository;
//
// 	@Autowired
// 	private BookingRepository bookingRepository;
//
// 	@Autowired
// 	private PaymentRepository paymentRepository;
//
// 	@Autowired
// 	private TrainTestHelper trainTestHelper;
//
// 	@Autowired
// 	private TrainScheduleTestHelper trainScheduleTestHelper;
//
// 	@Autowired
// 	private BookingTestHelper bookingTestHelper;
//
// 	private Member member;
//
// 	private Booking booking;
//
// 	@BeforeEach
// 	void beforeEach() {
// 		member = MemberFixture.create();
// 		memberRepository.save(member);
//
// 		Train train = trainTestHelper.createKTX();
// 		TrainScheduleWithStopStations scheduleWithStops = trainScheduleTestHelper.createSchedule(train);
// 		booking = bookingTestHelper.createPendingBooking(member, scheduleWithStops);
// 	}
//
//
// 	@Test
// 	@DisplayName("금액이 일치하지 않으면 결제가 실패한다")
// 	void processPayment_fail_whenAmountMismatch() {
// 	}
//
// 	@Test
// 	@DisplayName("다른 사용자의 예약으로는 결제할 수 없다")
// 	void processPayment_fail_whenNotOwner() {
// 	}
//
// 	@Test
// 	@DisplayName("이미 결제된 예약은 중복 결제할 수 없다")
// 	@Disabled
// 	void processPayment_fail_whenAlreadyPaid() {
// 	}

// 	@Test
// 	@DisplayName("다른 사용자의 결제는 취소할 수 없다")
// 	void cancelPayment_fail_whenNotOwner() {
// 	// given
// 	Member other = MemberFixture.createOtherMember();
// 	memberRepository.save(other);
//
// 	PaymentProcessCardRequest request = PaymentFixture.createCardPaymentRequest(
// 		booking.getId(), booking.getTotalFare());
// 	PaymentProcessResponse paymentResponse = paymentService
// 		.processPaymentViaCard(member.getMemberDetail().getMemberNo(), request);
//
// 	// when & then
// 	assertThatThrownBy(() -> paymentService.cancelPayment(
// 		other.getMemberDetail().getMemberNo(), paymentResponse.paymentKey()))
// 		.isInstanceOf(BusinessException.class)
// 		.hasMessage(PaymentError.PAYMENT_ACCESS_DENIED.getMessage());
// 	}
//
// 	@Test
// 	@DisplayName("결제 내역 조회가 성공한다")
// 	void getPaymentHistory_success() {
// 		// given
// 		// 카드 결제만 진행 (StationFare 중복 생성 문제 회피)
// 		PaymentProcessCardRequest cardRequest = PaymentFixture.createCardPaymentRequest(
// 			booking.getId(), booking.getTotalFare());
// 		paymentService.processPaymentViaCard(member.getMemberDetail().getMemberNo(), cardRequest);
//
// 		// when
// 		List<PaymentHistoryResponse> paymentHistory =
// 			paymentService.getPaymentHistory(member.getMemberDetail().getMemberNo());
//
// 		// then
// 		assertThat(paymentHistory).hasSize(1);
//
// 		PaymentHistoryResponse cardPayment = paymentHistory.get(0);
// 		assertThat(cardPayment.paymentMethod()).isEqualTo(PaymentMethod.CARD);
// 		assertThat(cardPayment.paymentStatus()).isEqualTo(PaymentStatus.PAID);
// 		assertThat(cardPayment.amount()).isEqualByComparingTo(booking.getTotalFare());
// 		assertThat(cardPayment.paymentKey()).isNotNull();
// 		assertThat(cardPayment.bookingCode()).isNotNull();
// 	}
//
// 	@Test
// 	@DisplayName("존재하지 않는 회원의 결제 내역 조회 시 예외가 발생한다")
// 	void getPaymentHistory_fail_whenMemberNotFound() {
// 		// when & then
// 		assertThatThrownBy(() -> paymentService.getPaymentHistory("nonexistent"))
// 			.isInstanceOf(BusinessException.class)
// 			.hasMessage("사용자를 찾을 수 없습니다.");
// 	}

}
