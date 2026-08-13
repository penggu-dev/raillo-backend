package com.sudo.raillo.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;

import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.DomainException;
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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_reservation_code", columnNames = "reservation_code")
	},
	indexes = {
		// 회원별 유효 예약 목록 조회
		@Index(name = "idx_reservation_member", columnList = "member_no, status, expires_at")
	}
)
public class Reservation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "reservation_id")
	@Comment("예약 ID")
	private Long id;

	@Column(name = "reservation_code", nullable = false)
	@Comment("고객·외부 노출용 예약 코드")
	private String reservationCode;

	@Column(name = "member_no", nullable = false)
	@Comment("회원 번호")
	private String memberNo;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_schedule_id", nullable = false)
	@Comment("운행 일정 ID")
	private TrainSchedule trainSchedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "departure_stop_id", nullable = false)
	@Comment("출발 정류장 ID")
	private ScheduleStop departureStop;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "arrival_stop_id", nullable = false)
	@Comment("도착 정류장 ID")
	private ScheduleStop arrivalStop;

	@Column(name = "total_fare", nullable = false)
	@Comment("총 운임")
	private BigDecimal totalFare;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("예약 상태")
	private ReservationStatus status;

	@Column(name = "expires_at", nullable = false)
	@Comment("예약 만료 시각 (Redis TTL 대체)")
	private LocalDateTime expiresAt;

	public static Reservation create(
		String reservationCode,
		String memberNo,
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		BigDecimal totalFare,
		LocalDateTime expiresAt
	) {
		Reservation reservation = new Reservation();
		reservation.reservationCode = reservationCode;
		reservation.memberNo = memberNo;
		reservation.trainSchedule = trainSchedule;
		reservation.departureStop = departureStop;
		reservation.arrivalStop = arrivalStop;
		reservation.totalFare = totalFare;
		reservation.expiresAt = expiresAt;
		reservation.status = ReservationStatus.HELD;
		return reservation;
	}

	/**
	 * 결제 완료로 예약을 확정한다.
	 */
	public void confirm() {
		validateIsHeld();
		this.status = ReservationStatus.CONFIRMED;
	}

	/**
	 * 사용자 취소 또는 만료로 예약을 해제한다.
	 */
	public void release() {
		validateIsHeld();
		this.status = ReservationStatus.RELEASED;
	}

	public boolean isExpired(LocalDateTime now) {
		return !this.expiresAt.isAfter(now);
	}

	/**
	 * 결제를 진행할 수 있는 상태인지 여부 (점유중이면서 만료되지 않음)
	 */
	public boolean isPayable(LocalDateTime now) {
		return this.status == ReservationStatus.HELD && !isExpired(now);
	}

	public int getDepartureStopOrder() {
		return this.departureStop.getStopOrder();
	}

	public int getArrivalStopOrder() {
		return this.arrivalStop.getStopOrder();
	}

	private void validateIsHeld() {
		if (this.status != ReservationStatus.HELD) {
			throw new DomainException(BookingError.RESERVATION_NOT_HELD);
		}
	}
}
