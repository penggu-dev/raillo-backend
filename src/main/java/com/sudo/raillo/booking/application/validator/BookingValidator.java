package com.sudo.raillo.booking.application.validator;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.SeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.status.OperationStatus;
import com.sudo.raillo.train.exception.TrainErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BookingValidator {

	/**
	 * 출발지, 도착지 순서 검증
	 */
	public void validateStopSequence(ScheduleStop departureStop, ScheduleStop arrivalStop) {
		if (departureStop.getStopOrder() > arrivalStop.getStopOrder()) {
			throw new BusinessException(BookingError.TRAIN_NOT_OPERATIONAL);
		}
	}

	/**
	 * 출발역, 도착역이 같은 스케줄을 가지고 있는지 검증
	 * */
	public void validateSameSchedule(ScheduleStop departureStop, ScheduleStop arrivalStop) {
		if (!departureStop.getTrainSchedule().getId().equals(arrivalStop.getTrainSchedule().getId())) {
			throw new BusinessException(TrainErrorCode.INVALID_ROUTE);
		}
	}

	/**
	 * 열차 스케줄 운행 여부 확인
	 * */
	public void validateTrainOperating(TrainSchedule trainSchedule) {
		if (trainSchedule.getOperationStatus() == OperationStatus.CANCELLED) {
			throw new BusinessException(TrainErrorCode.TRAIN_OPERATION_CANCELLED);
		}
	}

	/**
	 * 기존 예약들과 충돌 검증 (락이 걸린 상태에서 수행)
	 */
	public void validateConflictWithExistingBookings(
		Booking newBooking,
		List<SeatBooking> existingBookings
	) {
		int newDepartureOrder = newBooking.getDepartureStop().getStopOrder();
		int newArrivalOrder = newBooking.getArrivalStop().getStopOrder();

		existingBookings.forEach(existingBooking -> {
			int existingDepartureOrder = existingBooking.getBooking().getDepartureStop().getStopOrder();
			int existingArrivalOrder = existingBooking.getBooking().getArrivalStop().getStopOrder();
			if (existingDepartureOrder < newArrivalOrder && existingArrivalOrder > newDepartureOrder) {
				throw new BusinessException(BookingError.SEAT_ALREADY_BOOKED);
			}
		});
	}

	/**
	 * 요청된 승객 수와 선택한 좌석 수의 일치 여부를 검증
	 * */
	public void validatePassengerSeatCount(List<PassengerType> passengerTypes, List<Long> seatIds) {
		// 요청 승객 수와 선택한 좌석 수를 비교하여 좌석 수가 승객 수보다 많으면 오류 발생
		if (passengerTypes.size() != seatIds.size()) {
			throw new BusinessException(BookingError.BOOKING_CREATE_SEATS_INVALID);
		}
	}

	/**
	 * 임시 예약 접근 권한 확인
	 */
	public void validatePendingBookingOwnership(List<PendingBooking> pendingBookings, String memberNo) {
		List<PendingBooking> invalidBookings = pendingBookings.stream()
			.filter(booking -> !booking.getMemberNo().equals(memberNo))
			.toList();

		if (!invalidBookings.isEmpty()) {
			log.warn("권한 없는 임시예약 접근 시도 - 요청회원:{}, 임시예약 ID:{}",
				memberNo, invalidBookings.stream().map(PendingBooking::getId).toList());
			throw new BusinessException(BookingError.PENDING_BOOKING_ACCESS_DENIED);
		}
	}

}
