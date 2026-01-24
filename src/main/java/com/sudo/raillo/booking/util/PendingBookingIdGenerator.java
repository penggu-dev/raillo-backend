package com.sudo.raillo.booking.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

@Component
public class PendingBookingIdGenerator {

	private static final String PREFIX = "PB";
	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final int RANDOM_LENGTH = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * PendingBookingId 생성
	 * 형식: PB + yyyyMMddHHmmss + 6자리 랜덤 문자열 (영대문자+숫자)
	 * 예: PB20260124120000A1B2C3
	 */
	public String generate() {
		StringBuilder sb = new StringBuilder();
		sb.append(PREFIX);
		sb.append(LocalDateTime.now().format(TIMESTAMP_FORMATTER));

		for (int i = 0; i < RANDOM_LENGTH; i++) {
			int index = RANDOM.nextInt(CHARACTERS.length());
			sb.append(CHARACTERS.charAt(index));
		}

		return sb.toString();
	}
}
