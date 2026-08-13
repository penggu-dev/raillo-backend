package com.sudo.raillo.booking.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.sudo.raillo.booking.domain.status.SeatOccupancyStatus;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.TrainSchedule;
import com.sudo.raillo.train.domain.type.CarType;

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
		// 좌석 충돌 방어의 유일한 지점
		@UniqueConstraint(
			name = "uk_seat_occupancy_section",
			columnNames = {"train_schedule_id", "seat_id", "section_order"}
		)
	},
	indexes = {
		// 열차 검색 - CarType별 점유 좌석 수 집계
		@Index(name = "idx_seat_occupancy_section", columnList = "train_schedule_id, section_order, expires_at"),
		// 객차 목록·좌석 상세 - 객차별 점유 좌석 조회
		@Index(name = "idx_seat_occupancy_car", columnList = "train_schedule_id, train_car_id, section_order, expires_at"),
		// 예약 단위 확정·해제
		@Index(name = "idx_seat_occupancy_reservation", columnList = "reservation_id"),
		// 예매 단위 해제
		@Index(name = "idx_seat_occupancy_booking", columnList = "booking_id")
	}
)
public class SeatOccupancy extends BaseEntity {

	/**
	 * CONFIRMED 점유의 만료 시각 센티넬.
	 * <p>먼 미래값을 넣어 모든 조회 필터를 {@code expiresAt > now} 하나로 통일한다.</p>
	 */
	public static final LocalDateTime NEVER_EXPIRES = LocalDateTime.of(9999, 12, 31, 0, 0);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "seat_occupancy_id")
	@Comment("좌석 점유 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "train_schedule_id", nullable = false)
	@Comment("운행 일정 ID")
	private TrainSchedule trainSchedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seat_id", nullable = false)
	@Comment("좌석 ID")
	private Seat seat;

	@Column(name = "section_order", nullable = false)
	@Comment("점유 구간 (stopOrder i → i+1 의 i)")
	private int sectionOrder;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("점유 상태")
	private SeatOccupancyStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reservation_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예약 ID (예약에서 비롯된 점유)")
	private Reservation reservation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("예매 ID (확정 후 채워짐)")
	private Booking booking;

	// 역정규화 필드 - 집계 시 seat/train_car 조인 제거용
	@Column(name = "train_car_id", nullable = false)
	@Comment("객차 ID")
	private Long trainCarId;

	@Enumerated(EnumType.STRING)
	@Column(name = "car_type", nullable = false)
	@Comment("객차 타입")
	private CarType carType;

	@Column(name = "expires_at", nullable = false)
	@Comment("점유 만료 시각 (CONFIRMED는 9999-12-31 센티넬)")
	private LocalDateTime expiresAt;

	/**
	 * 예약에 의한 임시 점유(HELD) 행 생성
	 *
	 * @param sectionOrder 단위 구간 (stopOrder i → i+1 의 i)
	 */
	public static SeatOccupancy createHeld(Reservation reservation, Seat seat, int sectionOrder) {
		SeatOccupancy seatOccupancy = newOccupancy(reservation.getTrainSchedule(), seat, sectionOrder);
		seatOccupancy.status = SeatOccupancyStatus.HELD;
		seatOccupancy.reservation = reservation;
		seatOccupancy.expiresAt = reservation.getExpiresAt();
		return seatOccupancy;
	}

	/**
	 * 예매에 의한 확정 점유(CONFIRMED) 행 생성
	 *
	 * <p>예약을 거치지 않고 예매가 직접 만들어지는 경로에서만 사용한다.
	 * 정상 결제 플로우는 기존 HELD 행을 {@link #confirm(Booking)} 으로 전이시킨다.</p>
	 */
	public static SeatOccupancy createConfirmed(Booking booking, Seat seat, int sectionOrder) {
		SeatOccupancy seatOccupancy = newOccupancy(booking.getTrainSchedule(), seat, sectionOrder);
		seatOccupancy.status = SeatOccupancyStatus.CONFIRMED;
		seatOccupancy.booking = booking;
		seatOccupancy.expiresAt = NEVER_EXPIRES;
		return seatOccupancy;
	}

	/**
	 * 결제 완료로 점유를 확정한다. 행을 옮기지 않으므로 점유가 끊기는 순간이 없다.
	 */
	public void confirm(Booking booking) {
		this.status = SeatOccupancyStatus.CONFIRMED;
		this.booking = booking;
		this.expiresAt = NEVER_EXPIRES;
	}

	public boolean isExpired(LocalDateTime now) {
		return !this.expiresAt.isAfter(now);
	}

	private static SeatOccupancy newOccupancy(TrainSchedule trainSchedule, Seat seat, int sectionOrder) {
		SeatOccupancy seatOccupancy = new SeatOccupancy();
		seatOccupancy.trainSchedule = trainSchedule;
		seatOccupancy.seat = seat;
		seatOccupancy.sectionOrder = sectionOrder;
		// 역정규화 필드 설정
		seatOccupancy.trainCarId = seat.getTrainCar().getId();
		seatOccupancy.carType = seat.getTrainCar().getCarType();
		return seatOccupancy;
	}
}
