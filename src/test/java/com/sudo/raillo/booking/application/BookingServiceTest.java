package com.sudo.raillo.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.booking.application.dto.request.PendingBookingCreateRequest;
import com.sudo.raillo.booking.application.dto.request.BookingDeleteRequest;
import com.sudo.raillo.booking.application.dto.response.BookingDetail;
import com.sudo.raillo.booking.application.service.BookingService;
import com.sudo.raillo.booking.domain.Booking;
import com.sudo.raillo.booking.domain.status.BookingStatus;
import com.sudo.raillo.booking.domain.type.PassengerSummary;
import com.sudo.raillo.booking.domain.type.PassengerType;
import com.sudo.raillo.booking.infrastructure.BookingRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import com.sudo.raillo.support.helper.BookingTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper;
import com.sudo.raillo.support.helper.TrainScheduleTestHelper.TrainScheduleWithStopStations;
import com.sudo.raillo.support.helper.TrainTestHelper;
import com.sudo.raillo.train.domain.Train;
import com.sudo.raillo.train.domain.type.CarType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
@Slf4j
class BookingServiceTest {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private TrainTestHelper trainTestHelper;

	@Autowired
	private TrainScheduleTestHelper trainScheduleTestHelper;

	@Autowired
	private BookingTestHelper bookingTestHelper;

	@Autowired
	private BookingRepository bookingRepository;

	private Member member;
	private Train train;
	private TrainScheduleWithStopStations schedule;
	private List<Long> standardSeatIds;

	@BeforeEach
	void setup() {
		Member member = MemberFixture.createStandardMember();
		this.member = memberRepository.save(member);
		train = trainTestHelper.createKTX();
		schedule = trainScheduleTestHelper.createSchedule(train);
		standardSeatIds = trainTestHelper.getSeatIds(train, CarType.STANDARD, 2);
	}

	@Test
	@DisplayName("유효한 요청으로 예약이 성공한다")
	void validRequest_createBooking_success() {
		// given
		PendingBookingCreateRequest request = new PendingBookingCreateRequest(
			schedule.trainSchedule().getId(),
			schedule.scheduleStops().get(0).getId(),
			schedule.scheduleStops().get(1).getId(),
			List.of(PassengerType.ADULT, PassengerType.CHILD),
			standardSeatIds
		);

		// when
		Booking booking = bookingService.createBooking(request, member.getMemberDetail().getMemberNo());

		// then
		Booking savedBooking = bookingRepository.findById(booking.getId())
			.orElseThrow(() -> new AssertionError("예약이 DB에 저장되지 않았습니다"));

		assertThat(savedBooking.getMember().getId()).isEqualTo(member.getId());
		assertThat(savedBooking.getBookingStatus()).isEqualTo(BookingStatus.BOOKED);
		assertThat(savedBooking.getBookingCode()).isNotNull();
	}

	@Test
	@DisplayName("멤버번호와 예약 ID로 특정 예약 조회에 성공한다")
	void memberNoAndBookingId_getBooking_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		Booking entity = bookingRepository.save(booking);

		// when
		BookingDetail result = bookingService.getBooking(memberNo, entity.getId());

		// then
		assertThat(result.bookingId()).isEqualTo(entity.getId());
		assertThat(result.bookingCode()).isEqualTo(booking.getBookingCode());
		assertThat(result.departureStationName()).isEqualTo(
			schedule.scheduleStops().get(0).getStation().getStationName());
		assertThat(result.arrivalStationName()).isEqualTo(
			schedule.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("올바른 멤버번호와 잘못된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndInvalidBookingId_getBooking_throwException() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		bookingRepository.save(booking);

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, 2L))
			.isInstanceOf(BusinessException.class);

		bookingRepository.save(booking);
	}

	@Test
	@DisplayName("올바른 멤버번호와 만료된 예약 ID로 특정 예약 조회 시 예외를 반환한다")
	void memberNoAndExpiredBookingId_getBooking_throwException() {
		/*// given
		String memberNo = member.getMemberDetail().getMemberNo();
		Booking booking = Booking.builder()
			.trainSchedule(schedule.trainSchedule())
			.member(member)
			.bookingCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(schedule.scheduleStops().get(0))
			.arrivalStop(schedule.scheduleStops().get(1))
			.build();
		Booking entity = bookingRepository.save(booking);

		// when & then
		assertThatThrownBy(() -> bookingService.getBooking(memberNo, entity.getId()))
			.isInstanceOf(BusinessException.class)
			.hasMessage(BookingError.BOOKING_EXPIRED.getMessage());*/
	}

	@Test
	@DisplayName("멤버번호로 관련한 예약 목록 조회에 성공한다")
	void memberNo_getBookings_success() {
		// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Booking booking1 = bookingTestHelper.createBooking(member, scheduleBusanToDongDaegu);
		Booking booking2 = bookingTestHelper.createBooking(member, scheduleDaejeonToSeoul);
		Booking entity1 = bookingRepository.save(booking1);
		Booking entity2 = bookingRepository.save(booking2);

		// when
		List<BookingDetail> result = bookingService.getBookings(memberNo);

		// then
		assertThat(result.size()).isEqualTo(2);
		BookingDetail result1 = result.get(0);
		BookingDetail result2 = result.get(1);

		assertThat(result1.bookingId()).isEqualTo(entity1.getId());
		assertThat(result1.bookingCode()).isEqualTo(booking1.getBookingCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleBusanToDongDaegu.scheduleStops().get(1).getStation().getStationName());

		assertThat(result2.bookingId()).isEqualTo(entity2.getId());
		assertThat(result2.bookingCode()).isEqualTo(booking2.getBookingCode());
		assertThat(result2.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result2.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());
	}

	@Test
	@DisplayName("멤버번호로 예약 목록 조회 시 만료된 예약을 제외하고 조회에 성공한다")
	void memberNoAndExpiredBooking_getBookings_success() {
		/*// given
		String memberNo = member.getMemberDetail().getMemberNo();

		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleBusanToDongDaegu = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 부산에서 동대구")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("부산", null, LocalTime.of(5, 0))
			.addStop("동대구", LocalTime.of(8, 0), null)
			.build();

		TrainScheduleTestHelper.TrainScheduleWithStopStations scheduleDaejeonToSeoul = trainScheduleTestHelper.createCustomSchedule()
			.scheduleName("커스텀 노선 - 대전에서 서울")
			.operationDate(LocalDate.now())
			.train(train)
			.addStop("대전", null, LocalTime.of(10, 0))
			.addStop("서울", LocalTime.of(12, 0), null)
			.build();

		Booking booking1 = Booking.builder()
			.trainSchedule(scheduleBusanToDongDaegu.trainSchedule())
			.member(member)
			.bookingCode("20250806100001D49J")
			.tripType(TripType.OW)
			.totalPassengers(1)
			.passengerSummary("[{\"passengerType\":\"ADULT\",\"count\":1}]")
			.bookingStatus(BookingStatus.BOOKED)
			.expiresAt(LocalDateTime.now().minusMinutes(10))
			.fare(50000)
			.departureStop(scheduleBusanToDongDaegu.scheduleStops().get(0))
			.arrivalStop(scheduleBusanToDongDaegu.scheduleStops().get(1))
			.build();
		Booking booking2 = bookingTestHelper.createBooking(member, scheduleDaejeonToSeoul);
		Booking entity1 = bookingRepository.save(booking1);
		Booking entity2 = bookingRepository.save(booking2);

		// when
		List<BookingDetail> result = bookingService.getBookings(memberNo);

		// then
		assertThat(result.size()).isEqualTo(1);
		BookingDetail result1 = result.get(0);

		assertThat(result1.bookingId()).isEqualTo(entity2.getId());
		assertThat(result1.bookingCode()).isEqualTo(booking2.getBookingCode());
		assertThat(result1.departureStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(0).getStation().getStationName());
		assertThat(result1.arrivalStationName()).isEqualTo(
			scheduleDaejeonToSeoul.scheduleStops().get(1).getStation().getStationName());*/
	}

	@Test
	@DisplayName("올바른 예약 삭제 요청 DTO로 예약 삭제에 성공한다")
	void validRequestDto_deleteBooking_success() {
		// given
		Train train = trainTestHelper.createKTX();
		TrainScheduleTestHelper.TrainScheduleWithStopStations schedule = trainScheduleTestHelper.createSchedule(train);
		Booking booking = bookingTestHelper.createBooking(member, schedule);
		Booking entity = bookingRepository.save(booking);
		BookingDeleteRequest request = new BookingDeleteRequest(entity.getId());

		// when
		bookingService.deleteBooking(request.bookingId());

		// then
		List<Booking> result = bookingRepository.findAll();
		assertThat(result.size()).isEqualTo(0);
	}

}
