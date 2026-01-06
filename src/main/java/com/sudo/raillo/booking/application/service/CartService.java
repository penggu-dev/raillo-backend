package com.sudo.raillo.booking.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.BookingInfo;
import com.sudo.raillo.booking.application.dto.request.CartCreateRequest;
import com.sudo.raillo.booking.application.dto.response.BookingResponse;
import com.sudo.raillo.booking.application.mapper.BookingMapper;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Cart;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingQueryRepository;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.CartRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

	private final MemberRepository memberRepository;
	private final BookingRepository bookingRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final CartRepository cartRepository;
	private final BookingMapper bookingMapper;

	/**
	 * 장바구니에 예약 등록
	 */
	public void createCart(String memberNo, CartCreateRequest request) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		Booking booking = bookingRepository.findById(request.bookingId())
			.orElseThrow(() -> new BusinessException(BookingError.BOOKING_NOT_FOUND));

		// 본인 예약인지 확인
		validateBookingAccess(member, booking);

		// 장바구니에 등록된 예약인지 확인
		if (cartRepository.existsByBooking(booking)) {
			throw new BusinessException(BookingError.BOOKING_ALREADY_EXISTED);
		}

		Cart cart = Cart.create(member, booking);
		cartRepository.save(cart);
	}

	/**
	 * 장바구니에 등록한 예약 조회
	 */
	@Transactional(readOnly = true)
	public List<BookingResponse> getCarts(String memberNo) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		// 장바구니에 등록된 예약 ID 조회
		List<Long> bookingIds = cartRepository.findBookingIdsByMember(member);

		// 장바구니가 비어있다면 빈 응답 반환
		if (bookingIds.isEmpty()) {
			return List.of();
		}

		// 예약 조회
		List<BookingInfo> bookingInfos = bookingQueryRepository.findBookings(
			member.getId(), bookingIds);
		return bookingMapper.convertToBookingResponse(bookingInfos);
	}

	private void validateBookingAccess(Member member, Booking booking) {
		Long bookingMemberId = booking.getMember().getId();
		if (!bookingMemberId.equals(member.getId())) {
			log.warn("권한 없는 접근 시도: memberId={}, bookingId={}", member.getId(), booking.getId());
			throw new BusinessException(BookingError.BOOKING_ACCESS_DENIED);
		}
	}
}
