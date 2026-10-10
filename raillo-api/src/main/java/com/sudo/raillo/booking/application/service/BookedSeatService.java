package com.sudo.raillo.booking.application.service;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.application.dto.BookingConversionRequest;
import com.sudo.raillo.booking.application.dto.BookingSeatReleaseRequest;
import com.sudo.raillo.booking.infrastructure.BookingOccupancyCommand;
import com.sudo.raillo.booking.infrastructure.BookingSeatReleaseCommand;
import com.sudo.raillo.booking.infrastructure.ReservationRedisRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyCommand.SeatCar;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyRepository;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyResult;
import com.sudo.raillo.global.exception.BusinessException;
import com.sudo.raillo.train.cache.TrainCacheKey;

import lombok.RequiredArgsConstructor;

/**
 * `Booking` 좌석 점유({@code B:{bookingId}})의 생애주기를 다룬다. 결제가 확정되면 붙이고 `Booking`이 사라지면 뗀다.
 *
 * <p>DB를 보지 않으므로 트랜잭션을 열지 않는다. `Reservation` 점유({@code R:})는 {@link ReservationService}가
 * 다룬다. `Booking` 점유는 {@code bookingId}로만 식별되고 `Reservation`이 개입하지 않아 나눌 수 있다.</p>
 */
@Service
@RequiredArgsConstructor
public class BookedSeatService {

	private final SeatOccupancyRepository seatOccupancyRepository;
	private final ReservationRedisRepository reservationRedisRepository;

	/**
	 * 결제가 확정된 `Reservation`의 좌석을 `Booking` 점유로 바꾸고 회원 인덱스를 지운다. `Reservation` 본문은 스크립트가 지운다.
	 *
	 * @return 전환했으면 true, 다른 `Reservation`이나 `Booking`과 충돌해 아무것도 쓰지 않았으면 false
	 */
	public boolean convertToBooking(BookingConversionRequest request) {
		SeatOccupancyResult result = seatOccupancyRepository.confirmBooking(new BookingOccupancyCommand(
			request.trainScheduleId(),
			request.reservationId(),
			request.bookingId(),
			TrainCacheKey.expireAtEpochSecond(request.operationDate()),
			request.departureStopOrder(),
			request.arrivalStopOrder(),
			request.seats().stream()
				.map(seat -> new SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		));
		if (result.success()) {
			reservationRedisRepository.removeMemberIndex(request.memberNo(), request.reservationId());
		}
		return result.success();
	}

	/**
	 * 삭제된 `Booking`이 점유했던 좌석을 해제한다.
	 *
	 * <p>객차 점유 Hash({@code {schedule:N}:car:M:seats})에서 요청 구간의 field
	 * ({@code {seatId}:{sectionIndex}}, 구간은 {@code [출발, 도착)})를 지운다. 값이 정확히
	 * {@code B:{bookingId}}인 field만 지우므로 다른 `Booking`이나 `Reservation`의 점유는 남는다. `Reservation` 본문과 회원
	 * 인덱스는 건드리지 않는다.</p>
	 *
	 * @throws BusinessException 점유 해제 스크립트 실행이 실패했을 때
	 */
	public void releaseBookedSeats(BookingSeatReleaseRequest request) {
		seatOccupancyRepository.releaseBooking(new BookingSeatReleaseCommand(
			request.trainScheduleId(),
			request.bookingId(),
			request.departureStopOrder(),
			request.arrivalStopOrder(),
			request.seats().stream()
				.map(seat -> new SeatCar(seat.seatId(), seat.trainCarId()))
				.toList()
		));
	}
}
