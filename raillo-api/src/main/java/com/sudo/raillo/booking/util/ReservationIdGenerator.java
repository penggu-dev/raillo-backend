package com.sudo.raillo.booking.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

/**
 * 예약 ID 생성기. 형식은 {@code RV} + yyyyMMddHHmmss + 영대문자·숫자 6자리다. 예: {@code RV20260917120000A1B2C3}
 */
@Component
public class ReservationIdGenerator {

	public static final String PREFIX = "RV";
	public static final int LENGTH = 22;

	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final int RANDOM_LENGTH = 6;

	public String generate() {
		StringBuilder builder = new StringBuilder(LENGTH);
		builder.append(PREFIX);
		builder.append(LocalDateTime.now().format(TIMESTAMP_FORMATTER));

		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < RANDOM_LENGTH; i++) {
			builder.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
		}
		return builder.toString();
	}
}
