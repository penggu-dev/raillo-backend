package com.sudo.raillo.payment.application.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Schema(defaultValue = "결제 준비 요청")
public record PaymentPrepareRequest(

	@Schema(description = "예약 코드 목록")
	@NotEmpty(message = "예약 코드 목록은 필수입니다")
	@Size(min = 1, max = 5, message = "한 번에 최대 5개의 예약까지 결제 가능합니다")
	List<@NotBlank(message = "예약 코드는 공백일 수 없습니다") String> reservationCodes
) {
}
