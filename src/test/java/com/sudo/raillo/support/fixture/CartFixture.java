package com.sudo.raillo.support.fixture;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.Cart;
import com.sudo.raillo.member.domain.Member;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CartFixture {

	private Member member;
	private Booking booking;

	public static Cart create(Member member, Booking booking) {
		return builder()
			.withMember(member)
			.withBooking(booking)
			.build();
	}

	// builder method
	public static CartFixture builder() {
		return new CartFixture();
	}

	public Cart build() {
		return Cart.create(member, booking);
	}

	public CartFixture withMember(Member member) {
		this.member = member;
		return this;
	}

	public CartFixture withBooking(Booking booking) {
		this.booking = booking;
		return this;
	}
}
