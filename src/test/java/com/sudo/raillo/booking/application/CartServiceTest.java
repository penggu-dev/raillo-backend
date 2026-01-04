package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.application.dto.request.CartCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.service.CartService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.CartRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Train;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class CartServiceTest {

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private CartService cartService;

	@Autowired
	private CartRepository cartRepository;

	@Autowired
	private MemberRepository memberRepository;

	private String memberNo;
	private Booking booking;

	@BeforeEach
	void setUp() {
		Member member = MemberFixture.create();
		memberNo = member.getMemberDetail().getMemberNo();
		memberRepository.save(member);

		Train train = trainTestHelper.createKTX();
		TrainScheduleResult trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		booking = bookingTestHelper.createDefault(member, trainScheduleResult).booking();
	}

	@Test
	@DisplayName("장바구니에 예약을 등록하는데 성공한다")
	void createCart_success() {
		// given
		CartCreateRequest request = new CartCreateRequest(booking.getId());

		// when
		cartService.createCart(memberNo, request);

		// then
		boolean exists = cartRepository.existsByBooking(booking);
		assertThat(exists).isTrue();
	}

	@Test
	@DisplayName("자신의 예약이 아니라면 예외가 발생한다")
	void createCart_accessDenied_throwsException() {
		// given
		Member otherMember = MemberFixture.createOther();
		String otherMemberNo = otherMember.getMemberDetail().getMemberNo();
		memberRepository.save(otherMember);

		CartCreateRequest request = new CartCreateRequest(booking.getId());

		// when & then
		assertThatThrownBy(() -> cartService.createCart(otherMemberNo, request))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_ACCESS_DENIED.getMessage());
	}

	@Test
	@DisplayName("장바구니에 이미 등록된 예약이라면 예외가 발생한다")
	void createCart_duplicate_throwsException() {
		// given
		CartCreateRequest request = new CartCreateRequest(booking.getId());

		// when & then
		assertThatThrownBy(() -> {
			cartService.createCart(memberNo, request);
			cartService.createCart(memberNo, request);
		})
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_ALREADY_EXISTED.getMessage());
	}

	@Test
	@DisplayName("장바구니 조회에 성공한다")
	void getCarts_success() {
		// given
		CartCreateRequest request = new CartCreateRequest(booking.getId());
		cartService.createCart(memberNo, request);

		// when
		List<BookingDetail> cart = cartService.getCarts(memberNo);

		// then
		assertThat(cart).hasSize(1);

		BookingDetail detail = cart.get(0);
		assertThat(detail.bookingId()).isEqualTo(booking.getId());
		assertThat(detail.tickets()).isNotEmpty();
	}

	@Test
	@DisplayName("장바구니가 비어있다면 빈 응답을 반환한다")
	void getCarts_empty_success() {
		// when
		List<BookingDetail> cart = cartService.getCarts(memberNo);

		// then
		assertThat(cart).isEmpty();
	}
}
