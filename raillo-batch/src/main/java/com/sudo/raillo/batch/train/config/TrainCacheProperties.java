package com.sudo.raillo.batch.train.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 기준정보 Redis 적재 설정.
 */
@Getter
@Validated
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "train.cache")
public class TrainCacheProperties {

	// 운행일로부터 캐시를 유지할 일수
	@Min(1)
	private final int retentionDays;
	// 파이프라인 한 묶음에 보낼 명령 수
	@Min(1)
	private final int pipelineSize;
}
