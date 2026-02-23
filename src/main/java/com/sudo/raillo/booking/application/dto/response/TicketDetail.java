package com.sudo.raillo.booking.application.dto.response;

import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.train.domain.type.CarType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "예매 좌석 정보")
public record TicketDetail(

	@Schema(description = "승차권 ID", example = "1")
	Long ticketId,

	@Schema(description = "승차권 번호")
	String ticketNumber,

	@Schema(description = "승차권 상태", example = "ISSUED")
	TicketStatus status,

	@Schema(description = "승객 유형", example = "ADULT")
	PassengerType passengerType,

	@Schema(description = "객차 번호", example = "1")
	int carNumber,

	@Schema(description = "객차 타입 (STANDARD=일반실, FIRST_CLASS=특실)", example = "STANDARD")
	CarType carType,

	@Schema(description = "좌석 번호 (행 + 열)", example = "1D")
	String seatNumber
) {
}
