package com.sudo.raillo.booking.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.support.annotation.ServiceTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ServiceTest
class TicketNumberGeneratorTest {

	@Autowired
	private TicketNumberGenerator ticketNumberGenerator;

	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMdd");

	@Test
	@DisplayName("예약 순번이 올바른 형식으로 생성된다")
	void generateReservationCode_success() {
		// given
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		// when
		String reservationCode = ticketNumberGenerator.generateReservationCode();

		// then
		assertThat(reservationCode).matches("\\d{4}-\\d{7}");
		assertThat(reservationCode).isEqualTo(today + "-0000001");
	}

	@Test
	@DisplayName("연속으로 호출하면 예약 순번이 1씩 증가한다")
	void generate_sequence_increment() {
		// given
		String today = LocalDate.now(ZONE_ID).format(DATE_FORMATTER);

		// when
		String reservationCode1 = ticketNumberGenerator.generateReservationCode();
		String reservationCode2 = ticketNumberGenerator.generateReservationCode();
		String reservationCode3 = ticketNumberGenerator.generateReservationCode();

		// then
		assertThat(reservationCode1).isEqualTo(today + "-0000001");
		assertThat(reservationCode2).isEqualTo(today + "-0000002");
		assertThat(reservationCode3).isEqualTo(today + "-0000003");
	}

	@Test
	@DisplayName("예약 순번과 승차권 순번을 기반으로 승차권 번호가 올바른 형식으로 생성된다")
	void generateTicketNumber_success() {
		// given
		String reservationCode = ticketNumberGenerator.generateReservationCode();
		int ticketIndex = 1;

		// when
		String ticketNumber = ticketNumberGenerator.generateTicketNumber(reservationCode, ticketIndex);

		// then
		assertThat(ticketNumber).matches("\\d{4}-\\d{7}-\\d{2}");
		assertThat(ticketNumber).isEqualTo(reservationCode + "-01");
	}
}
