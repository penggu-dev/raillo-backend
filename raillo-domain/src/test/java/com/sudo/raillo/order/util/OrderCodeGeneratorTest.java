package com.sudo.raillo.order.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderCodeGeneratorTest {

	@Test
	@DisplayName("주문번호는 ORD_ 로 시작한다")
	void generate_startsWithPrefix() {
		// when
		String code = OrderCodeGenerator.generate();

		// then
		assertThat(code).startsWith("ORD_");
	}

	@Test
	@DisplayName("같은 초에 만든 주문번호 1만 개가 모두 다르다")
	void generate_isUniqueWithinSameSecond() {
		// given 시각 부분은 같은 초면 같으므로 유일성은 난수가 맡는다.
		// 난수가 네 자리(1만 가지)로 좁아지면 이 개수에서 반드시 겹친다.
		int count = 10_000;

		// when
		Set<String> codes = new HashSet<>();
		for (int i = 0; i < count; i++) {
			codes.add(OrderCodeGenerator.generate());
		}

		// then
		assertThat(codes).hasSize(count);
	}

	@Test
	@DisplayName("여러 스레드가 동시에 만들어도 주문번호가 겹치지 않는다")
	void generate_isUniqueAcrossThreads() throws Exception {
		// given
		int threads = 16;
		int perThread = 1_000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);

		// when
		List<Future<List<String>>> futures = new ArrayList<>();
		try {
			for (int t = 0; t < threads; t++) {
				Callable<List<String>> task = () -> {
					List<String> generated = new ArrayList<>(perThread);
					for (int i = 0; i < perThread; i++) {
						generated.add(OrderCodeGenerator.generate());
					}
					return generated;
				};
				futures.add(pool.submit(task));
			}

			Set<String> codes = new HashSet<>();
			for (Future<List<String>> future : futures) {
				codes.addAll(future.get());
			}

			// then
			assertThat(codes).hasSize(threads * perThread);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("주문번호는 Toss orderId 길이 제한 6~64자 안에 든다")
	void generate_fitsGatewayLengthLimit() {
		// when
		String code = OrderCodeGenerator.generate();

		// then
		assertThat(code.length()).isBetween(6, 64);
		assertThat(code).matches("[A-Za-z0-9_-]+");
	}
}
