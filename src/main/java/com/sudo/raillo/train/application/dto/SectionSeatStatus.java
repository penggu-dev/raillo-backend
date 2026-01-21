package com.sudo.raillo.train.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 구간별 좌석 상태 종합 정보
 * Service Layer에서 좌석 예약 가능 여부를 판단
 *
 * - 일반실/특실별 잔여 좌석 수
 * - 요청한 승객 수로 예약 가능 여부
 */
@Schema(description = "구간별 좌석 상태 종합 정보")
public record SectionSeatStatus(
	// 일반실 정보
	@Schema(description = "일반실 잔여 좌석 수", example = "45")
	int standardRemaining,

	@Schema(description = "일반실 전체 좌석 수", example = "246")
	int standardTotal,

	// 특실 정보
	@Schema(description = "특실 잔여 좌석 수", example = "12")
	int firstClassRemaining,

	@Schema(description = "특실 전체 좌석 수", example = "117")
	int firstClassTotal,

	// 예약 가능 여부
	@Schema(description = "승객수 고려한 일반실 예약 가능 여부", example = "true")
	boolean canReserveStandard,

	@Schema(description = "승객수 고려한 특실 예약 가능 여부", example = "true")
	boolean canReserveFirstClass

) {
}
