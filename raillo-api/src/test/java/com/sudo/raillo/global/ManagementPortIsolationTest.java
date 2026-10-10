package com.sudo.raillo.global;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;

import com.sudo.raillo.support.container.TestContainerInitializer;

/**
 * 관리 엔드포인트가 메인 포트와 분리된 채로 유지되는지 고정한다. 설계 근거는 docs/deployment.md에 있다.
 *
 * <p>스레드 풀 분리 자체는 포화를 재현해야 확인되므로 범위 밖이다. 여기서는 포트가 다르다는 것, 프로브가
 * 인증 없이 통과한다는 것, 종합 health와 무관하다는 것을 본다. kubelet은 토큰을 보내지 않으므로 두 번째가
 * 깨지면 파드가 기동 직후 재시작된다.</p>
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "management.server.port=0"
)
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainerInitializer.class)
class ManagementPortIsolationTest {

	@LocalServerPort private int serverPort;
	@LocalManagementPort private int managementPort;
	private final RestClient restClient = RestClient.create();

	@Test
	@DisplayName("관리 엔드포인트가 메인 포트와 다른 포트에서 뜬다")
	void managementRunsOnSeparatePort() {
		assertThat(managementPort).isNotEqualTo(serverPort);
	}

	@Test
	@DisplayName("liveness 프로브가 인증 없이 UP을 돌려준다")
	void livenessProbeIsOpenAndUp() {
		ResponseEntity<String> response = get("/actuator/health/liveness");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("UP");
	}

	@Test
	@DisplayName("liveness는 의존성 상태에 좌우되지 않는다")
	void livenessIgnoresDependencies() {
		// 이 환경에서는 메일 서버가 없어 종합 health가 DOWN이다. liveness가 그걸 따라가면 안 된다.
		assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(get("/actuator/health/liveness").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("readiness 프로브가 인증 없이 응답한다")
	void readinessProbeIsOpen() {
		ResponseEntity<String> response = get("/actuator/health/readiness");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("UP");
	}

	@Test
	@DisplayName("actuator는 메인 포트에 남지 않는다")
	void actuatorIsNotServedOnMainPort() {
		ResponseEntity<String> response = restClient.get()
			.uri("http://localhost:" + serverPort + "/actuator/health")
			.retrieve()
			.onStatus(status -> true, (request, res) -> { })
			.toEntity(String.class);

		assertThat(response.getStatusCode())
			.as("관리 포트를 분리하면 actuator 등록이 자식 컨텍스트로 옮겨간다")
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	private ResponseEntity<String> get(String path) {
		return restClient.get()
			.uri("http://localhost:" + managementPort + path)
			.retrieve()
			.onStatus(status -> true, (request, response) -> { })
			.toEntity(String.class);
	}
}
