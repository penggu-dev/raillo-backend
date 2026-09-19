package com.sudo.raillo.batch.train.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("TrainCacheProperties - 설정값 검증")
class TrainCachePropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfig.class);

	@DisplayName("정상 값이면 바인딩된다")
	@Test
	void binds_valid_values() {
		// given

		// when

		// then
		contextRunner
			.withPropertyValues("train.cache.retention-days=2", "train.cache.pipeline-size=1000")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(TrainCacheProperties.class).getPipelineSize()).isEqualTo(1000);
			});
	}

	@DisplayName("묶음 크기가 0이면 기동에 실패한다")
	@Test
	void rejects_zero_pipeline_size() {
		// given - 0이면 청크 반복문이 끝나지 않는다

		// when

		// then
		contextRunner
			.withPropertyValues("train.cache.retention-days=2", "train.cache.pipeline-size=0")
			.run(context -> assertThat(context).hasFailed());
	}

	@DisplayName("유지 일수가 0이면 기동에 실패한다")
	@Test
	void rejects_zero_retention_days() {
		// given - 0이면 당일 운행 키가 적재 직후 만료된다

		// when

		// then
		contextRunner
			.withPropertyValues("train.cache.retention-days=0", "train.cache.pipeline-size=1000")
			.run(context -> assertThat(context).hasFailed());
	}

	@Configuration
	@EnableConfigurationProperties(TrainCacheProperties.class)
	static class PropertiesConfig {
	}
}
