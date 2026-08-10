package com.sudo.raillo.support.container;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public class TestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final int REDIS_PORT = 6379;

	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.10"))
		.withDatabaseName("raillo_test")
		.withUrlParam("characterEncoding", "UTF-8")
		.withUrlParam("serverTimezone", "Asia/Seoul");

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(REDIS_PORT);

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
