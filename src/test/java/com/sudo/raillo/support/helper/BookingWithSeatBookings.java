package com.sudo.raillo.support.helper;

import java.util.List;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;

/**
 * 예약과 좌석 예약 정보를 함께 담는 테스트용 결과 객체.
 *
 * <p>{@link BookingTestHelper}에서 예약 생성 시 반환되며,
 * 생성된 {@link Booking}과 해당 예약에 속한 {@link SeatBooking} 목록을 함께 제공한다.</p>
 *
 * <h4>사용 예시</h4>
 * <pre>{@code
 * BookingWithSeats result = bookingTestHelper.createBooking(member, schedule);
 *
 * Booking booking = result.booking();
 * List<SeatBooking> seatBookings = result.seatBookings();
 * }</pre>
 *
 * @param booking 생성된 예약
 * @param seatBookings 해당 예약의 좌석 예약 목록
 */
public record BookingWithSeatBookings(Booking booking, List<SeatBooking> seatBookings) {
}
