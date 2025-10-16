package com.sudo.raillo.booking.application.generator;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

@Component
public class ReservationCodeGenerator {

	/***
	 * 고객용 예매번호를 생성하는 메서드
	 * @return 고객용 예매번호
	 */
	public String generateReservationCode() {
		// yyyyMMddHHmmss<랜덤4자리> 형식
		LocalDateTime now = LocalDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
		String dateTimeStr = now.format(formatter);

		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		StringBuilder randomStr = new StringBuilder();
		SecureRandom secureRandom = new SecureRandom();
		for (int i = 0; i < 4; i++) {
			int idx = secureRandom.nextInt(chars.length());
			randomStr.append(chars.charAt(idx));
		}
		return dateTimeStr + randomStr;
	}
}
