package com.sudo.raillo.booking.application.dto.request;

import com.sudo.raillo.booking.domain.type.PassengerSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "예약 생성 요청 DTO")
public record BookingCreateRequest(
	@Schema(description = "열차 스케줄 ID", example = "54")
	@NotNull(message = "열차 스케줄 ID는 필수입니다")
	Long trainScheduleId,

	@Schema(description = "출발역 ID", example = "2")
	@NotNull(message = "출발역 ID는 필수입니다")
	Long departureStationId,

	@Schema(description = "도착역 ID", example = "11")
	@NotNull(message = "도착역 ID는 필수입니다")
	Long arrivalStationId,

	@Schema(description = "승객 유형과 인원을 담은 오브젝트를 요소로 하는 리스트")
	@NotNull(message = "승객 정보는 필수입니다")
	List<PassengerSummary> passengers,

	@Schema(description = "좌석 ID를 요소로 하는 리스트", example = "[ 46456, 46457 ]")
	@NotNull(message = "좌석 정보는 필수입니다")
	List<Long> seatIds
) {
}
