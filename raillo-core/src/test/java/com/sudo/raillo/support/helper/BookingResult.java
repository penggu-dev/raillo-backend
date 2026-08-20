package com.sudo.raillo.support.helper;

import java.util.List;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.Ticket;

/**
 * 예매과 예매 좌석 정보를 함께 담는 테스트용 결과 객체.
 *
 * <p>{@link BookingTestHelper}에서 예매 생성 시 반환되며,
 * 생성된 {@link Booking}과 해당 예매에 속한 {@link SeatBooking} 목록을 함께 제공한다.</p>
 *
 * <h4>사용 예시</h4>
 * <pre>{@code
 * BookingWithSeats result = bookingTestHelper.createBooking(member, schedule);
 *
 * Booking booking = result.booking();
 * List<SeatBooking> seatBookings = result.seatBookings();
 * List<Ticket> tickets = result.tickets();
 * }</pre>
 *
 * @param booking 생성된 예매
 * @param seatBookings 해당 예매의 좌석 목록
 * @param tickets 생성된 승차권 목록
 */
public record BookingResult(Booking booking, List<SeatBooking> seatBookings, List<Ticket> tickets) {
}
