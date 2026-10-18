package com.sudo.raillo.payment.application.recovery;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RecoveryPropertiesTest {

	@Test
	@DisplayName("설정 블록이 없으면 운영과 같은 기본값으로 바인딩되고 스케줄러는 꺼진 채로 남는다")
	void bind_withoutAnyProperty_usesProductionDefaults() {
		// given
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

		// when
		RecoveryProperties properties = binder.bindOrCreate("raillo.payment.recovery", RecoveryProperties.class);

		// then
		assertThat(properties.enabled()).isFalse();
		assertThat(properties.pollingInterval()).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.staleAfter()).isEqualTo(Duration.ofMinutes(2));
		assertThat(properties.batchSize()).isEqualTo(20);
		assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(5));
		assertThat(properties.initialBackoff()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.maxBackoff()).isEqualTo(Duration.ofMinutes(30));
		assertThat(properties.maxWait()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	@DisplayName("최초 백오프가 0이면 나누기가 터지기 전에 설정을 거절한다")
	void create_zeroInitialBackoff_isRejected() {
		// given
		Duration zero = Duration.ZERO;

		// when & then
		assertThatThrownBy(() -> create(zero))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("initial-backoff");
	}

	@Test
	@DisplayName("최초 백오프가 비어 있으면 설정을 거절한다")
	void create_nullInitialBackoff_isRejected() {
		// given
		Duration missing = null;

		// when & then
		assertThatThrownBy(() -> create(missing))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("initial-backoff");
	}

	@Test
	@DisplayName("기간 설정이 음수면 어느 값인지 메시지에 담아 거절한다")
	void create_negativeMaxWait_isRejected() {
		// given
		Duration negative = Duration.ofHours(-1);

		// when & then
		assertThatThrownBy(() -> new RecoveryProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(2), 20,
			Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(30), negative))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-wait");
	}

	private RecoveryProperties create(Duration initialBackoff) {
		return new RecoveryProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(2), 20,
			Duration.ofMinutes(5), initialBackoff, Duration.ofMinutes(30), Duration.ofHours(24));
	}
}
