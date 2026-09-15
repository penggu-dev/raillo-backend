package com.sudo.raillo.order.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class OrderCodeGenerator {

	private static final SecureRandom random = new SecureRandom();

	public static String generate() {
		String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));

		int randomNumber = random.nextInt(10000);
		String numericSuffix = String.format("%04d", randomNumber);

		return "ORD_" + dateTime + numericSuffix;
	}
}
