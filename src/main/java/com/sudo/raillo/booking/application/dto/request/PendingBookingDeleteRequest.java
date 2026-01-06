package com.sudo.raillo.booking.application.dto.request;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

@Schema(description = "임시 예약 삭제 요청 DTO")
public record PendingBookingDeleteRequest(

	@Schema(description = "삭제할 임시 예약 ID 리스트", example = "[ e029f64f-405e-49c7-87a3-5a0c32607142, 7b2c87b9-b1ca-4a0a-9bdf-d4b7f0b92892 ]")
	@NotEmpty(message = "삭제할 임시 예약 ID는 필수입니다")
	List<String> pendingBookingIds
) {
}
