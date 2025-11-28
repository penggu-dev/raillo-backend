package com.sudo.raillo.booking.domain;

import com.sudo.raillo.booking.application.generator.BookingCodeGenerator;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.TripType;
import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.train.domain.ScheduleStop;
import com.sudo.raillo.train.domain.TrainSchedule;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.util.List;
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	@Comment("여행 타입")
	private TripType tripType;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(
		name = "booking_passenger_summary",
		joinColumns = @JoinColumn(name = "booking_id")
	)
	@Comment("유형 별 승객 수")
	private List<PassengerSummary> passengerSummary;

	@Column(nullable = false)
	@Comment("만료 시간")
	private LocalDateTime expiresAt;

	@Comment("결제 완료 시간")
	private LocalDateTime purchaseAt;

	@Comment("반환(취소) 시간")
	private LocalDateTime cancelledAt;

	@Column(nullable = false)
	@Comment("운임")
	private int fare;

	public void approve() {
		this.bookingStatus = BookingStatus.PAID;
		this.purchaseAt = LocalDateTime.now();
	}

	public void cancel() {
		this.bookingStatus = BookingStatus.CANCELLED;
		this.cancelledAt = LocalDateTime.now();
	}

	public void refund() {
		this.bookingStatus = BookingStatus.REFUNDED;
	}

	// 결제 가능 여부 확인
	public boolean canBePaid() {
		return this.bookingStatus.isPayable();
	}

	// 취소 가능 여부 확인
	public boolean canBeCancelled() {
		return this.bookingStatus.isCancellable();
	}

	// 환불 가능 여부 확인
	public boolean canBeRefunded() {
		return this.purchaseAt != null && this.bookingStatus.isRefundable();
	}

	// 총 승객수 조회
	public int getTotalPassengers() {
		return passengerSummary.stream()
			.mapToInt(PassengerSummary::getCount)
			.sum();
	}
}
