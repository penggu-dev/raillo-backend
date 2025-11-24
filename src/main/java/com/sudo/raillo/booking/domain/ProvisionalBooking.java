package com.sudo.raillo.booking.domain;

import java.time.LocalDateTime;
import java.util.List;

import com.sudo.raillo.booking.application.dto.request.ReservationCreateRequest;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.TripType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProvisionalBooking {

	private String bookingId;
	private String memberNo;

	private Long trainScheduleId;
	private Long departureStationId;
	private Long arrivalStationId;

	private List<PassengerSummary> passengers;

	private List<Long> seatIds;

	private TripType tripType;

	private LocalDateTime createdAt;
	private LocalDateTime expiresAt;

	private Integer totalFare;

	public static ProvisionalBooking from(
		String bookingId,
		String memberId,
		ReservationCreateRequest request,
		Integer totalFare
	) {
		return ProvisionalBooking.builder()
			.bookingId(bookingId)
			.memberNo(memberId)
			.trainScheduleId(request.trainScheduleId())
			.departureStationId(request.departureStationId())
			.arrivalStationId(request.arrivalStationId())
			.passengers(request.passengers())
			.seatIds(request.seatIds())
			.tripType(request.tripType())
			.createdAt(LocalDateTime.now())
			.expiresAt(LocalDateTime.now().plusMinutes(10))
			.totalFare(totalFare)
			.build();
	}
}
