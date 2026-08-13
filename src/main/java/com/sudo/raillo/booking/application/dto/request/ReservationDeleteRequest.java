package com.sudo.raillo.booking.application.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

@Schema(description = "예약 삭제 요청 DTO")
public record ReservationDeleteRequest(

	@Schema(description = "삭제할 예약 코드 리스트", example = "[ PB20260814120000A1B2C3, PB20260814120500D4E5F6 ]")
	@NotEmpty(message = "삭제할 예약 코드는 필수입니다")
	List<String> reservationCodes
) {
}
