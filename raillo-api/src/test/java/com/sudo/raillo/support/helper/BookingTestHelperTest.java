package com.sudo.raillo.support.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.order.domain.OrderBooking;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.train.domain.Train;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@ServiceTest
public class BookingTestHelperTest {

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private OrderTestHelper orderTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Test
	@DisplayName("orderBooking으로 Booking을 생성시 orderBooking과 booking의 trainSchedule이 일치한다")
	void createByOrderBookingTest() {
		// given
		Member member = memberRepository.save(MemberFixture.create());
		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		OrderResult orderResult = orderTestHelper.createDefault(member, trainScheduleResult);
		OrderBooking orderBooking = orderResult.orderBookings().get(0);

		// when
		BookingResult bookingResult = bookingTestHelper.createByOrderBooking(orderBooking);

		// then
		assertThat(bookingResult.booking().getTrainSchedule()).isEqualTo(orderBooking.getTrainSchedule());
	}
}
