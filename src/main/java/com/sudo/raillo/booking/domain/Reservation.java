package com.sudo.raillo.booking.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "reservation_id")
	@Comment("예약 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_schedule_id", nullable = false)
	@Comment("운행 일정 ID")
	private TrainSchedule trainSchedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("멤버 ID")
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "departure_stop_id", nullable = false)
	@Comment("출발 정류장 ID")
	private ScheduleStop departureStop;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "arrival_stop_id", nullable = false)
	@Comment("도착 정류장 ID")
	private ScheduleStop arrivalStop;

	@Column(nullable = false)
	@Comment("고객용 예매 코드")
	private String reservationCode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("여행 타입")
	private TripType tripType;

	@Column(nullable = false)
	@Comment("총 승객 수")
	private int totalPassengers;

	@Column(nullable = false)
	@Comment("유형 별 승객 수")
	private String passengerSummary;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("예약 상태")
	private ReservationStatus reservationStatus;

	@Column(nullable = false)
	@Comment("운임")
	private int fare;

	@Column
	@Comment("결제 ID")
	private Long paymentId;

	/**
	 * 결제 완료 후 확정 예약 생성 (ProvisionalBooking → Reservation)
	 */
	public static Reservation fromProvisionalBooking(
		ProvisionalBooking provisional,
		TrainSchedule trainSchedule,
		Member member,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		String reservationCode,
		String passengerSummaryJson,
		Long paymentId
	) {
		return Reservation.builder()
			.trainSchedule(trainSchedule)
			.member(member)
			.reservationCode(reservationCode)
			.tripType(provisional.getTripType())
			.totalPassengers(provisional.getPassengers().stream()
				.mapToInt(PassengerSummary::getCount)
				.sum())
			.passengerSummary(passengerSummaryJson)
			.reservationStatus(ReservationStatus.PAID)
			.fare(provisional.getTotalFare())
			.departureStop(departureStop)
			.arrivalStop(arrivalStop)
			.paymentId(paymentId)
			.build();
	}

	public void cancel() {
		if (!reservationStatus.isCancellable()) {
			throw new BusinessException(BookingError.RESERVATION_NOT_CANCELLABLE);
		}
		this.reservationStatus = ReservationStatus.CANCELLED;
	}

	public void refund() {
		if (!reservationStatus.isRefundable()) {
			throw new BusinessException(BookingError.RESERVATION_NOT_REFUNDABLE);
		}
		this.reservationStatus = ReservationStatus.REFUNDED;
	}

	// 취소 가능 여부 확인
	public boolean canBeCancelled() {
		return this.reservationStatus.isCancellable();
	}
}
