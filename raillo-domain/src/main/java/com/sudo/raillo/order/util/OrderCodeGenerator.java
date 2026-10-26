package com.sudo.raillo.order.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public class OrderCodeGenerator {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyMMddHHmmss");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int RANDOM_BYTES = 8;

	private OrderCodeGenerator() {
	}

	/** 주문번호. Toss에 orderId로 넘긴다. 시각은 읽기용이고 유일성은 난수와 {@code order_code}의 unique 제약이 보장한다. */
	public static String generate() {
		byte[] bytes = new byte[RANDOM_BYTES];
		RANDOM.nextBytes(bytes);
		return "ORD_" + LocalDateTime.now().format(FORMATTER) + HexFormat.of().formatHex(bytes);
	}
}
