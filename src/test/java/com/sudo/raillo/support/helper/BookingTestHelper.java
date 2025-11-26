package com.sudo.raillo.support.helper;

import static com.sudo.raillo.support.helper.TrainScheduleTestHelper.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.booking.infrastructure.SeatBookingRepository;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Transactional
public class BookingTestHelper {

	private final TrainTestHelper trainTestHelper;
	private final BookingRepository bookingRepository;
	private final SeatBookingRepository seatBookingRepository;

	/**
	 * 기본 예약 생성 메서드
	 */
	public Booking createBooking(Member member,
										 TrainScheduleWithStopStations scheduleWithStops) {
		Booking booking = Booking.builder()
			.trainSchedule(scheduleWithStops.trainSchedule())
			.member(member)
			.bookingCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().plusMinutes(10))
			.fare(50000)
			.departureStop(getDepartureStop(scheduleWithStops.scheduleStops()))
			.arrivalStop(getArrivalStop(scheduleWithStops.scheduleStops()))
			.build();

		bookingRepository.save(booking);
		createSeatBooking(booking);
		return booking;
	}

	/**
	 * 특정 좌석 ID들에 대한 좌석 예약 생성 (출발, 도착 정차역 직접 지정)
	 *
	 * @param member 예약할 회원
	 * @param scheduleWithStops 열차 스케줄 및 정차역 정보
	 * @param departureStop 출발 정차역
	 * @param arrivalStop 도착 정차역
	 * @param seatIds 예약할 좌석 ID 목록
	 * @param passengerType 승객 유형 (성인, 어린이 등)
	 * @return 생성된 Booking 객체
	 */
	public Booking createBookingWithSeatIds(Member member,
													TrainScheduleWithStopStations scheduleWithStops,
													ScheduleStop departureStop,
													ScheduleStop arrivalStop,
													List<Long> seatIds,
													PassengerType passengerType) {

		Booking booking = Booking.builder()
			.trainSchedule(scheduleWithStops.trainSchedule())
			.member(member)
			.bookingCode("SEAT-" + System.currentTimeMillis())
			.tripType(TripType.OW)
			.totalPassengers(seatIds.size())
			.passengerSummary("[{\"passengerType\":\"" + passengerType.name() + "\",\"count\":" + seatIds.size() + "}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().plusMinutes(10))
			.fare(50000 * seatIds.size())
			.departureStop(departureStop)
			.arrivalStop(arrivalStop)
			.build();

		bookingRepository.save(booking);
		createSeatBookings(booking, seatIds, passengerType);
		return booking;
	}

	/**
	 * 좌석 예약 생성 메서드
	 */
	private void createSeatBooking(Booking booking) {
		Train train = booking.getTrainSchedule().getTrain();
		List<Seat> seats = trainTestHelper.getSeats(train, CarType.STANDARD, 1);

		List<SeatBooking> seatBookings = seats.stream()
			.map(seat -> SeatBooking.builder()
				.trainSchedule(booking.getTrainSchedule())
				.seat(seat)
				.booking(booking)
				.passengerType(PassengerType.ADULT)
				.build())
			.toList();

		seatBookingRepository.saveAll(seatBookings);
	}

	/**
	 * 주어진 좌석 ID들로 SeatBooking 생성
	 */
	private void createSeatBookings(Booking booking, List<Long> seatIds, PassengerType passengerType) {
		List<Seat> seats = trainTestHelper.getSeatsByIds(seatIds);

		List<SeatBooking> seatBookings = seats.stream()
			.map(seat -> SeatBooking.builder()
				.trainSchedule(booking.getTrainSchedule())
				.seat(seat)
				.booking(booking)
				.passengerType(passengerType)
				.build())
			.toList();

		seatBookingRepository.saveAll(seatBookings);
	}

	private ScheduleStop getDepartureStop(List<ScheduleStop> scheduleStops) {
		if (scheduleStops.isEmpty()) {
			throw new IllegalArgumentException("출발역을 찾을 수 없습니다.");
		}
		return scheduleStops.get(0);
	}

	private ScheduleStop getArrivalStop(List<ScheduleStop> scheduleStops) {
		if (scheduleStops.isEmpty()) {
			throw new IllegalArgumentException("도착역을 찾을 수 없습니다.");
		}
		return scheduleStops.get(scheduleStops.size() - 1);
	}
}
