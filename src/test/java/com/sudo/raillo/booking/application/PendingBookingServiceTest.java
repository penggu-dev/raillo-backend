package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;

import com.sudo.raillo.booking.application.dto.response.PendingBookingDetail;
import com.sudo.raillo.booking.application.dto.response.PendingSeatBookingDetail;
import com.sudo.raillo.booking.application.service.PendingBookingService;
import com.sudo.raillo.booking.domain.PendingBooking;
import com.sudo.raillo.booking.domain.PendingSeatBooking;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.BookingRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.fixture.PendingBookingFixture;
import com.sudo.raillo.support.helper.TrainScheduleResult;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Seat;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import com.sudo.raillo.train.exception.TrainErrorCode;

@ServiceTest
class PendingBookingServiceTest {

	@Autowired
	private PendingBookingService pendingBookingService;

	@Autowired
	private BookingRedisRepository bookingRedisRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	private Member testMember;
	private Train train;
	private TrainScheduleResult trainScheduleResult;
	private List<Seat> seats;
	private Train otherTrain;
	private TrainScheduleResult otherTrainScheduleResult;
	private List<Seat> otherSeats;

	@BeforeEach
	void setUp() {
		testMember = MemberFixture.create();

		train = trainTestHelper.createCustomKTX(3, 2);
		trainScheduleResult = trainScheduleTestHelper.createDefault(train);
		seats = trainTestHelper.getSeats(train, CarType.STANDARD, 4);

		otherTrain = trainTestHelper.createCustomKTX(2, 1);
		otherTrainScheduleResult = trainScheduleTestHelper.builder()
			.scheduleName("KTX 002 경부선")
			.train(otherTrain)
			.operationDate(LocalDate.now().plusDays(1))
			.addStop("서울", null, LocalTime.of(9, 0))
			.addStop("대전", LocalTime.of(12, 0), null)
			.build();
		otherSeats = trainTestHelper.getSeats(otherTrain, CarType.STANDARD, 2);
	}

	@Test
	@DisplayName("회원 번호로 임시 예약 목록 조회에 성공한다")
	void getPendingBookings_success() {
		// given
		// 같은 열차 스케줄의 좌석 1개 예약
		PendingBooking pendingBooking1 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(0).getId(), PassengerType.ADULT)
				)
			)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking1);

		// 같은 열차 스케줄의 좌석 2개 예약
		PendingBooking pendingBooking2 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(1).getId(), PassengerType.ADULT),
					new PendingSeatBooking(seats.get(2).getId(), PassengerType.CHILD)
				)
			)
			.withTotalFare(BigDecimal.valueOf(75000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking2);

		// when
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());

		// then
		assertThat(result).hasSize(2);

		// 첫 번째 임시 예약 검증
		PendingBookingDetail detail1 = result.stream()
			.filter(detail -> detail.pendingBookingId().equals(pendingBooking1.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(detail1.trainNumber()).isEqualTo(String.format("%03d", train.getTrainNumber()));
		assertThat(detail1.trainName()).isEqualTo(train.getTrainName());
		assertThat(detail1.departureStationName()).isEqualTo(trainScheduleResult.scheduleStops().get(0).getStation()
			.getStationName());
		assertThat(detail1.arrivalStationName()).isEqualTo(trainScheduleResult.scheduleStops().get(1).getStation()
			.getStationName());
		assertThat(detail1.seats()).hasSize(1);
		assertThat(detail1.seats().get(0).passengerType()).isEqualTo(PassengerType.ADULT);

		// 두 번째 임시 예약 검증
		PendingBookingDetail detail2 = result.stream()
			.filter(detail -> detail.pendingBookingId().equals(pendingBooking2.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(detail2.seats()).hasSize(2);
		assertThat(detail2.seats())
			.extracting(PendingSeatBookingDetail::passengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.CHILD);
	}

	@Test
	@DisplayName("회원의 서로 다른 열차 스케줄의 임시 예약 정보를 조회해오는데 성공한다")
	void getPendingBooking_success_differentSchedule() {
		// given
		PendingBooking pendingBooking1 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(0).getId(), PassengerType.ADULT)
				)
			)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking1);

		// 열차 스케줄이 다른 임시 예약
		PendingBooking pendingBooking2 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(otherTrainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(otherTrainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(otherTrainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(otherSeats.get(0).getId(), PassengerType.ADULT),
					new PendingSeatBooking(otherSeats.get(1).getId(), PassengerType.CHILD)
				)
			)
			.withTotalFare(BigDecimal.valueOf(75000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking2);

		// when
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());

		// then
		assertThat(result).hasSize(2);

		// 첫 번째 임시 예약 (서울 -> 부산)
		PendingBookingDetail detail1 = result.stream()
			.filter(detail -> detail.pendingBookingId().equals(pendingBooking1.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(detail1.trainNumber()).isEqualTo(String.format("%03d", train.getTrainNumber()));
		assertThat(detail1.trainName()).isEqualTo(train.getTrainName());
		assertThat(detail1.departureStationName()).isEqualTo("서울");
		assertThat(detail1.arrivalStationName()).isEqualTo("부산");
		assertThat(detail1.seats()).hasSize(1);
		assertThat(detail1.seats().get(0).passengerType()).isEqualTo(PassengerType.ADULT);

		// 두 번째 임시 예약 (서울 -> 대전)
		PendingBookingDetail detail2 = result.stream()
			.filter(detail -> detail.pendingBookingId().equals(pendingBooking2.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(detail2.trainNumber()).isEqualTo(String.format("%03d", otherTrain.getTrainNumber()));
		assertThat(detail2.trainName()).isEqualTo(otherTrain.getTrainName());
		assertThat(detail2.departureStationName()).isEqualTo("서울");
		assertThat(detail2.arrivalStationName()).isEqualTo("대전");
		assertThat(detail2.seats()).hasSize(2);
		assertThat(detail2.seats())
			.extracting(PendingSeatBookingDetail::passengerType)
			.containsExactlyInAnyOrder(PassengerType.ADULT, PassengerType.CHILD);
	}

	@Test
	@DisplayName("임시 예약이 없는 경우 빈 리스트를 반환한다")
	void getPendingBookings_success_emptyList() {
		// when
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());

		// then
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("임시 예약의 열차 스케줄이 DB에 존재하지 않으면 예외가 발생한다")
	void getPendingBookings_fail_trainScheduleNotFound() {
		// given
		Long notExistTrainScheduleId = 999L;

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(notExistTrainScheduleId)
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(0).getId(), PassengerType.ADULT)
				)
			)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() -> pendingBookingService.getPendingBookings(testMember.getMemberDetail().getMemberNo()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TrainErrorCode.TRAIN_SCHEDULE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("임시 예약의 정차역이 DB에 존재하지 않으면 예외가 발생한다")
	void getPendingBookings_fail_stationNotFound() {
		// given
		Long nonExistStopId = 999L;

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(nonExistStopId)
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(0).getId(), PassengerType.ADULT)
				)
			)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() -> pendingBookingService.getPendingBookings(testMember.getMemberDetail().getMemberNo()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TrainErrorCode.STATION_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("임시 예약의 예약 좌석이 DB에 존재하지 않으면 예외가 발생한다")
	void getPendingBookings_fail_seatNotFound() {
		// given
		Long nonExistSeatId = 999L;

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withTrainScheduleId(trainScheduleResult.trainSchedule().getId())
			.withDepartureStopId(trainScheduleResult.scheduleStops().get(0).getId())
			.withArrivalStopId(trainScheduleResult.scheduleStops().get(1).getId())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(nonExistSeatId, PassengerType.ADULT)
				)
			)
			.withTotalFare(BigDecimal.valueOf(50000))
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() -> pendingBookingService.getPendingBookings(testMember.getMemberDetail().getMemberNo()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.SEAT_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("단일 임시 예약 삭제에 성공한다")
	void deletePendingBookings_success_single() {
		// given
		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when
		pendingBookingService.deletePendingBookings(
			List.of(pendingBooking.getId()),
			testMember.getMemberDetail().getMemberNo()
		);

		// then
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("다중 임시 예약 삭제에 성공한다")
	void deletePendingBookings_success_multiple() {
		// given
		PendingBooking pendingBooking1 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking1);

		PendingBooking pendingBooking2 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(1).getId(), PassengerType.ADULT),
					new PendingSeatBooking(seats.get(2).getId(), PassengerType.CHILD)
				)
			)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking2);

		PendingBooking pendingBooking3 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(0).getId(), PassengerType.ADULT)
				)
			)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking3);

		// when
		pendingBookingService.deletePendingBookings(
			List.of(pendingBooking1.getId(), pendingBooking2.getId(), pendingBooking3.getId()),
			testMember.getMemberDetail().getMemberNo()
		);

		// then
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("일부 임시 예약만 삭제하면 나머지 예약은 유지된다")
	void deletePendingBookings_success_partial() {
		// given
		PendingBooking pendingBooking1 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking1);

		PendingBooking pendingBooking2 = PendingBookingFixture.builder()
			.withMemberNo(testMember.getMemberDetail().getMemberNo())
			.withPendingSeatBookings(
				List.of(
					new PendingSeatBooking(seats.get(1).getId(), PassengerType.ADULT),
					new PendingSeatBooking(seats.get(2).getId(), PassengerType.CHILD)
				)
			)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking2);

		// when
		pendingBookingService.deletePendingBookings(
			List.of(pendingBooking1.getId()), // 첫 번째 예약만 삭제
			testMember.getMemberDetail().getMemberNo()
		);

		// then
		List<PendingBookingDetail> result = pendingBookingService.getPendingBookings(
			testMember.getMemberDetail().getMemberNo());
		assertThat(result).hasSize(1);
		assertThat(result.get(0).pendingBookingId()).isEqualTo(pendingBooking2.getId());
	}

	@Test
	@DisplayName("존재하지 않는 임시 예약 ID로 삭제해도 예외가 발생하지 않는다")
	void deletePendingBookings_success_nonExistId() {
		// given
		List<String> nonExistIds = List.of(
			"nonexist-9087-4aea-aca2-7ced03617c11",
			"nonexist-af60-4b7b-a7b9-9c6cae9cad0a"
		);

		// when & then
		assertThatCode(() ->
			pendingBookingService.deletePendingBookings(
				nonExistIds,
				testMember.getMemberDetail().getMemberNo()
			)
		).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("권한이 없는 임시 예약을 삭제하려고 시도하면 예외가 발생한다")
	void deletePendingBookings_fail_notOwner() {
		// given
		String ownerMemberNo = "owner_member_no";
		String nonOwnerMemberNo = "non_owner_member_no";

		PendingBooking pendingBooking = PendingBookingFixture.builder()
			.withMemberNo(ownerMemberNo)
			.build();
		bookingRedisRepository.savePendingBooking(pendingBooking);

		// when & then
		assertThatThrownBy(() ->
			pendingBookingService.deletePendingBookings(
				List.of(pendingBooking.getId()),
				nonOwnerMemberNo
			)).isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.PENDING_BOOKING_ACCESS_DENIED.getMessage());
	}
}
