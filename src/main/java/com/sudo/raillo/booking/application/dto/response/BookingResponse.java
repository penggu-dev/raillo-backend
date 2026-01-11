package com.sudo.raillo.booking.application.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예매 정보")
public record BookingResponse(

	@Schema(description = "예매 ID", example = "1")
	Long bookingId,

	@Schema(description = "예매 코드", example = "202507110020301F8C")
	String bookingCode,

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

	@Schema(description = "승차권 목록")
	List<TicketDetail> tickets
) {
}
