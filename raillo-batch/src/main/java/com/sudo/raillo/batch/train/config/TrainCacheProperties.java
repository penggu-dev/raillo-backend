package com.sudo.raillo.batch.train.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기준정보 Redis 적재 설정.
 */
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "train.cache")
public class TrainCacheProperties {

	// 운행일로부터 캐시를 유지할 일수
	private final int retentionDays;
	// 파이프라인 한 묶음에 보낼 명령 수
	private final int pipelineSize;
}
