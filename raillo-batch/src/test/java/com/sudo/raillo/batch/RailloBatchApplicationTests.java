package com.sudo.raillo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.sudo.raillo.batch.support.BatchTestContainerInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = BatchTestContainerInitializer.class)
class RailloBatchApplicationTests {

	@DisplayName("배치 애플리케이션이 웹 서버 없이 시작된다")
	@Test
	void context_loads_without_web_server(ApplicationContext context) {
		// given

		// when
		String webApplicationType = context.getEnvironment()
			.getProperty("spring.main.web-application-type");

		// then
		assertThat(webApplicationType).isEqualTo("none");
		assertThat(context.getClass().getName()).doesNotContain("WebServer");
	}

}
