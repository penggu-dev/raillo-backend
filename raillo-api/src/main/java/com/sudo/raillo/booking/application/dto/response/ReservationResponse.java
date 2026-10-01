package com.sudo.raillo.booking.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예약 조회 응답 DTO")
public record ReservationResponse(
	@Schema(description = "예약 ID", example = "RV20260917120000A1B2C3")
	String reservationId,

	@Schema(description = "열차 번호", example = "101")
	int trainNumber,

	@Schema(description = "열차 이름", example = "KTX")
	String trainName,

	@Schema(description = "운행일", example = "2026-10-20")
	LocalDate operationDate,

	@Schema(description = "출발 정차역")
	StopResponse departure,

	@Schema(description = "도착 정차역")
	StopResponse arrival,

	@Schema(description = "출발 일시", example = "2026-10-20T06:00:00")
	LocalDateTime departureAt,

	@Schema(description = "객차 종류", example = "STANDARD")
	CarType carType,

	@Schema(description = "예약 좌석 목록")
	List<SeatResponse> seats,

	@Schema(description = "총 운임", example = "59800")
	BigDecimal totalFare,

	@Schema(description = "예약 만료 일시. 이 시각까지 결제를 시작해야 한다", example = "2026-09-17T12:10:00")
	LocalDateTime expiresAt
) {

	@Schema(description = "정차역 정보")
	public record StopResponse(
		@Schema(description = "역 이름", example = "서울")
		String stationName,

		@Schema(description = "출발역은 출발 시각, 도착역은 도착 시각", example = "06:00:00")
		LocalTime time
	) {
	}

	@Schema(description = "예약 좌석 정보")
	public record SeatResponse(
		@Schema(description = "객차 번호", example = "3")
		int carNumber,

		@Schema(description = "좌석 번호", example = "12A")
		String seatLabel,

		@Schema(description = "승객 유형", example = "ADULT")
		PassengerType passengerType,

		@Schema(description = "운임", example = "59800")
		BigDecimal fare
	) {
	}
}
