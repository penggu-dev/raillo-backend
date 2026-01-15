package com.sudo.raillo.global.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 파드 식별자
 * K8s 환경에서는 HOSTNAME 환경변수로 파드 이름이 자동 주입됨
 */
@Component
public class PodIdentifier {

	@Value("${HOSTNAME:local-dev}")
	private String hostname;

	/**
	 * 현재 파드 ID 반환
	 * - K8s: pod-name-xxxx
	 * - 로컬: local-dev
	 */
	public String get() {
		return hostname;
	}
}
