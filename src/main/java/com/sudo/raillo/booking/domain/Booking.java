package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.application.generator.BookingCodeGenerator;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.DomainException;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "booking_id")
	@Comment("예약 ID")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	@Comment("멤버 ID")
	private Member member;

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("예약 상태")
	private BookingStatus bookingStatus;

	@Column(nullable = false)
	@Comment("고객용 예매 코드")
	private String bookingCode;

	@Column(nullable = false)
	@Comment("운임")
	private BigDecimal totalFare;

	@Comment("반환(취소) 시간")
	private LocalDateTime cancelledAt;

	public static Booking create(
		Member member,
		TrainSchedule trainSchedule,
		ScheduleStop departureStop,
		ScheduleStop arrivalStop,
		BigDecimal totalFare
	) {
		Booking booking = new Booking();
		booking.member = member;
		booking.trainSchedule = trainSchedule;
		booking.departureStop = departureStop;
		booking.arrivalStop = arrivalStop;
		booking.bookingStatus = BookingStatus.BOOKED;
		booking.bookingCode = BookingCodeGenerator.generateBookingCode();
		validateTotalFare(totalFare);
		booking.totalFare = totalFare;
		return booking;
	}

	public void cancel() {
		validateIsNotCancelled();
		this.bookingStatus = BookingStatus.CANCELLED;
		this.cancelledAt = LocalDateTime.now();
	}

	private static void validateTotalFare(BigDecimal totalAmount) {
		if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
			throw new DomainException(BookingError.INVALID_TOTAL_FAIR);
		}
	}

	private void validateIsNotCancelled() {
		if (this.bookingStatus == BookingStatus.CANCELLED) {
			throw new DomainException(BookingError.BOOKING_ALREADY_CANCELLED);
		}
	}
}
