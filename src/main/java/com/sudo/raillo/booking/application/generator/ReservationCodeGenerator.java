package com.sudo.raillo.booking.application.generator;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

@Component
public class ReservationCodeGenerator {

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final int RANDOM_LENGTH = 4;
	private final SecureRandom secureRandom = new SecureRandom();

	/***
	 * 고객용 예매번호를 생성하는 메서드
	 * @return 고객용 예매번호
	 */
	public String generateReservationCode() {
		// yyyyMMddHHmmss<랜덤4자리> 형식
		LocalDateTime now = LocalDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
		String dateTimeStr = now.format(formatter);

		StringBuilder randomStr = new StringBuilder();
		for (int i = 0; i < RANDOM_LENGTH; i++) {
			int idx = secureRandom.nextInt(CHARS.length());
			randomStr.append(CHARS.charAt(idx));
		}
		return dateTimeStr + randomStr;
	}
}
