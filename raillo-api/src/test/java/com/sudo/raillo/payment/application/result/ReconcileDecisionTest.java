package com.sudo.raillo.payment.application.result;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReconcileDecision - 회복 판정 계약")
class ReconcileDecisionTest {

	@Test
	@DisplayName("판정 구현이 모두 record면 소비자 switch가 새 판정을 빠뜨렸을 때 컴파일이 멈춘다")
	void permittedSubclasses_stayFlatRecords() {
		// given
		Class<?>[] permitted = ReconcileDecision.class.getPermittedSubclasses();

		// when
		List<String> notRecords = Arrays.stream(permitted)
			.filter(subclass -> !subclass.isRecord())
			.map(Class::getSimpleName)
			.toList();

		// then
		assertThat(permitted).isNotEmpty();
		assertThat(notRecords)
			.as("중간 sealed 타입으로 판정을 묶으면 그 아래 새 판정이 추가돼도 소비자 switch가 컴파일되어 "
				+ "조용히 기존 응답을 받는다. 판정은 평평하게 유지한다")
			.isEmpty();
	}
}
