package com.sudo.raillo.payment.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.Order;
import com.sudo.raillo.order.domain.status.OrderStatus;
import com.sudo.raillo.order.infrastructure.OrderRepository;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
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
class PaymentFacadeTest {

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

		List<Long> seatIds = trainTestHelper.getSeatIds(
			trainScheduleResult.trainSchedule().getTrain(),
			CarType.STANDARD,
			1
		);

		List<PendingSeatBooking> pendingSeatBookings = List.of(
			new PendingSeatBooking(seatIds.get(0), PassengerType.ADULT)
		);

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(memberNo)
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(departureStop.getId())
			.withArrivalStopId(arrivalStop.getId())
			.withPendingSeatBookings(pendingSeatBookings)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		PaymentPrepareRequest request = new PaymentPrepareRequest(List.of(pendingBooking.getId()));

		// when
		PaymentPrepareResponse response = paymentFacade.preparePayment(request, memberNo);

		// then
		Order order = orderRepository.findByOrderCode(response.orderId()).get();
		assertThat(response.orderId()).isEqualTo(order.getOrderCode());
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(response.amount()).isEqualByComparingTo(pendingBooking.getTotalFare());
	}
}
