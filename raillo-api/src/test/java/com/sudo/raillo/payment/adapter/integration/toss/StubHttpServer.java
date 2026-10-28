package com.sudo.raillo.payment.adapter.integration.toss;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * 전송 계층 검증용 로컬 HTTP 서버. MockRestServiceServer는 request factory를 대체해
 * Apache의 재시도 실행 체인을 지나지 않으므로 전송 계층 동작을 볼 수 없다.
 */
final class StubHttpServer implements AutoCloseable {

	private static final String BODY = "{\"code\":\"STUB\",\"message\":\"stub\"}";

	private final HttpServer server;
	private final AtomicInteger requestCount = new AtomicInteger();

	private StubHttpServer(HttpServer server) {
		this.server = server;
	}

	/** 모든 요청에 {@code status}를 즉시 돌려주는 서버를 띄운다. */
	static StubHttpServer start(int status) {
		return start(status, Duration.ZERO);
	}

	/** 응답 전에 {@code delay}만큼 멈추는 서버를 띄운다. 타임아웃 검증에 쓴다. */
	static StubHttpServer start(int status, Duration delay) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			StubHttpServer stub = new StubHttpServer(server);
			server.createContext("/", exchange -> {
				stub.requestCount.incrementAndGet();
				sleep(delay);
				byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			return stub;
		} catch (IOException e) {
			throw new IllegalStateException("스텁 서버를 띄우지 못했다", e);
		}
	}

	private static void sleep(Duration delay) {
		if (delay.isZero() || delay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	int requestCount() {
		return requestCount.get();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
