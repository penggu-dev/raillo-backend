package com.sudo.raillo.batch.support;

import java.util.Map;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public class BatchTestContainerInitializer
	implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final MySQLContainer<?> MYSQL =
		new MySQLContainer<>(DockerImageName.parse("mysql:8.4.10"))
			.withDatabaseName("raillo_batch_test")
			.withUrlParam("characterEncoding", "UTF-8")
			.withUrlParam("serverTimezone", "Asia/Seoul")
			.withTmpFs(Map.of("/var/lib/mysql", "rw"))
			.withReuse(true);

	static {
		MYSQL.start();
	}

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		TestPropertyValues.of(
			"spring.datasource.url=" + MYSQL.getJdbcUrl(),
			"spring.datasource.username=" + MYSQL.getUsername(),
			"spring.datasource.password=" + MYSQL.getPassword(),
			"spring.datasource.driver-class-name=" + MYSQL.getDriverClassName()
		).applyTo(applicationContext);
	}
}
