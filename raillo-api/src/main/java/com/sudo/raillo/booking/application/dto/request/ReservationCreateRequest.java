package com.sudo.raillo.booking.application.dto.request;

import java.util.List;

import com.sudo.raillo.booking.domain.type.PassengerType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "예약 생성 요청 DTO")
public record ReservationCreateRequest(
	@Schema(description = "열차 스케줄 ID", example = "54")
	@NotNull(message = "열차 스케줄 ID는 필수입니다")
	Long trainScheduleId,

	@Schema(description = "출발역 ID", example = "2")
	@NotNull(message = "출발역 ID는 필수입니다")
	Long departureStationId,

	@Schema(description = "도착역 ID", example = "11")
	@NotNull(message = "도착역 ID는 필수입니다")
	Long arrivalStationId,

	@Schema(description = "승객 유형 리스트. 좌석 ID 리스트와 순서·개수가 같아야 한다", example = "[\"ADULT\", \"CHILD\"]")
	@NotEmpty(message = "승객 정보는 필수입니다")
	@Size(max = MAX_PASSENGERS, message = "승객 수는 최대 9명까지 가능합니다")
	List<@NotNull(message = "승객 유형에 빈 값이 있습니다") PassengerType> passengerTypes,

	@Schema(description = "좌석 ID 리스트", example = "[46456, 46457]")
	@NotEmpty(message = "좌석 정보는 필수입니다")
	@Size(max = MAX_PASSENGERS, message = "좌석은 최대 9개까지 선택할 수 있습니다")
	List<@NotNull(message = "좌석 ID에 빈 값이 있습니다") Long> seatIds
) {

	/** 한 예약에 담을 수 있는 최대 승객 수. 열차 검색 요청과 같은 값이다. */
	public static final int MAX_PASSENGERS = 9;
}
