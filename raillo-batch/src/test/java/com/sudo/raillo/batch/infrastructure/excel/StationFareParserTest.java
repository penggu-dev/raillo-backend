package com.sudo.raillo.batch.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class StationFareParserTest {

	@DisplayName("classpath 운임 리소스를 열면 운임 데이터가 있는 시트를 반환한다")
	@Test
	void loads_packaged_station_fare_resource() {
		// given
		var parser = new StationFareParser();
		ReflectionTestUtils.setField(
			parser, "resource", new ClassPathResource("files/station_fare.xls"));

		// when
		var sheets = parser.getSheets();

		// then
		assertThat(sheets).isNotEmpty();
		assertThat(sheets.getFirst().getPhysicalNumberOfRows()).isPositive();
	}
}
