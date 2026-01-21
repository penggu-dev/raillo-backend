package com.sudo.raillo.booking.application.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 정보")
public record PendingBookingDetailResponse(

	@Schema(description = "예약 ID", example = "3e10feba-fd72-48f1-8c05-28b0035d52de")
	String pendingBookingId,

	@Schema(description = "열차 번호", example = "027")
	String trainNumber,

	@Schema(description = "열차명", example = "KTX")
	String trainName,

	@Schema(description = "출발역명", example = "서울")
	String departureStationName,

	@Schema(description = "도착역명", example = "천안아산")
	String arrivalStationName,

	@Schema(description = "출발 시간", example = "11:58")
	LocalTime departureTime,

	@Schema(description = "도착 시간", example = "12:38")
	LocalTime arrivalTime,

	@Schema(description = "운행일", example = "2025-07-01")
	LocalDate operationDate,

	@Schema(description = "예약 좌석 정보")
	List<PendingSeatBookingDetail> seats
) {
}
