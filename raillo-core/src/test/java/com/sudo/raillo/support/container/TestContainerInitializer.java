package com.sudo.raillo.support.container;

import java.util.Map;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class TestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final int REDIS_PORT = 6379;

	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.10"))
		.withDatabaseName("raillo_test")
		.withUrlParam("characterEncoding", "UTF-8")
		.withUrlParam("serverTimezone", "Asia/Seoul")
		// 데이터 디렉터리를 RAM에 올려 파일 I/O 비용을 줄임
		.withTmpFs(Map.of("/var/lib/mysql", "rw"))
		.withReuse(true);

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(REDIS_PORT)
		// Reids 기동 완료 로그가 출력되면 연결
		.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
		.withReuse(true);

	// Testcontainers는 Ryuk이라는 리소스 리퍼 컨테이너를 함께 띄워 테스트 프로세스 종료 시 자동으로 정리하므로 stop() 호출이 필요 없음
	static {
		MYSQL.start();
		REDIS.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		TestPropertyValues.of(
			"spring.datasource.url=" + MYSQL.getJdbcUrl(),
			"spring.datasource.username=" + MYSQL.getUsername(),
			"spring.datasource.password=" + MYSQL.getPassword(),
			"spring.datasource.driver-class-name=" + MYSQL.getDriverClassName(),
			"spring.data.redis.host=" + REDIS.getHost(),
			"spring.data.redis.port=" + REDIS.getMappedPort(REDIS_PORT)
		).applyTo(applicationContext);
	}
}
