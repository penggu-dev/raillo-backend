package com.sudo.raillo.train.application.dto.response;

import com.sudo.raillo.train.domain.type.SeatAvailabilityStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "좌석 유형별 정보")
public record SeatTypeInfo(

	@Schema(description = "잔여 좌석 수", example = "45")
	int remainingSeats,

	@Schema(description = "전체 좌석 수", example = "300")
	int totalSeats,

	@Schema(description = "요금", example = "14100")
	BigDecimal fare,

	@Schema(description = "좌석 상태", example = "AVAILABLE")
	SeatAvailabilityStatus status,

	@Schema(description = "예약 가능 여부", example = "true")
	boolean canReserve,

	@Schema(description = "화면 표시용 텍스트", example = "일반실")
	String displayText,

	@Schema(description = "상세 설명", example = "잔여 45석")
	String description
) {

	public static SeatTypeInfo create(
		int availableSeats,
		int totalSeats,
		BigDecimal fare,
		int passengerCount,
		String seatTypeName,
		boolean canReserve
	) {
		SeatAvailabilityStatus status = determineSeatStatus(availableSeats, passengerCount, totalSeats);

		String displayText = createDisplayText(status, seatTypeName);
		String description = createDescription(status, availableSeats, passengerCount);

		return new SeatTypeInfo(availableSeats, totalSeats, fare, status, canReserve, displayText, description);
	}

	/**
	 * 좌석 수와 승객 수로 예약 가능한 좌석 상태 결정
	 */
	private static SeatAvailabilityStatus determineSeatStatus(int availableSeats, int passengerCount, int totalSeats) {
		// 좌석 매진
		if (availableSeats == 0) {
			return SeatAvailabilityStatus.SOLD_OUT;
		}

		// 좌석 부족
		if (availableSeats < passengerCount) {
			// 인원수 보다 예약 가능한 좌석이 적어 불가능하다면 INSUFFICIENT
			return SeatAvailabilityStatus.INSUFFICIENT;
		}

		double availabilityRatio = (double)availableSeats / totalSeats;

		if (availabilityRatio >= 0.25) {
			return SeatAvailabilityStatus.AVAILABLE; // 25% 이상이면 여유
		} else {
			return SeatAvailabilityStatus.LIMITED;
		}
	}

	/**
	 * 화면 표시용 텍스트 생성
	 */
	private static String createDisplayText(SeatAvailabilityStatus status, String seatTypeName) {
		return switch (status) {
			case SOLD_OUT -> status.getText();           // "매진"
			case INSUFFICIENT -> status.getText();       // "좌석부족"
			case LIMITED -> seatTypeName + "(" + status.getText() + ")";  // "일반실(매진임박)"
			case AVAILABLE -> seatTypeName;              // "일반실" / "특실"
		};
	}

	/**
	 * 상세 설명 생성 (enum description + 구체적 수치)
	 */
	private static String createDescription(SeatAvailabilityStatus status, int availableSeats,
		int passengerCount) {
		return switch (status) {
			case SOLD_OUT -> status.getDescription();
			case INSUFFICIENT -> String.format("%s (%d명 예약 시 %d석 부족)",
				status.getDescription(), passengerCount, passengerCount - availableSeats);
			case LIMITED -> String.format("%s (잔여 %d석)", status.getDescription(), availableSeats);
			case AVAILABLE -> String.format("잔여 %d석", availableSeats);
		};
	}
}
