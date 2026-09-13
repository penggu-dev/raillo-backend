package com.sudo.raillo.batch.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

class TrainScheduleParserTest {

	@DisplayName("classpath 시간표 리소스를 열면 파싱 대상 시트를 반환한다")
	@Test
	void loads_packaged_schedule_resource() {
		// given
		var parser = new TrainScheduleParser();
		ReflectionTestUtils.setField(
			parser, "resource", new ClassPathResource("files/train_schedule.xlsx"));

		// when
		var sheets = parser.getSheets();

		// then
		assertThat(sheets).isNotEmpty();
		assertThat(sheets).noneMatch(sheet -> sheet.getSheetName().contains("총괄"));
	}

	@DisplayName("외부 파일 시간표 리소스를 지정하면 동일하게 파싱 대상 시트를 반환한다")
	@Test
	void loads_external_schedule_resource(@TempDir Path tempDirectory) throws IOException {
		// given
		Path scheduleFile = tempDirectory.resolve("train_schedule.xlsx");
		try (var input = new ClassPathResource("files/train_schedule.xlsx").getInputStream()) {
			Files.copy(input, scheduleFile);
		}
		var parser = new TrainScheduleParser();
		ReflectionTestUtils.setField(parser, "resource", new FileSystemResource(scheduleFile));

		// when
		var sheets = parser.getSheets();

		// then
		assertThat(sheets).isNotEmpty();
	}

	@DisplayName("시간표 리소스가 없으면 파일 읽기 실패 예외를 발생시킨다")
	@Test
	void rejects_missing_schedule_resource() {
		// given
		var parser = new TrainScheduleParser();
		ReflectionTestUtils.setField(parser, "resource", new FileSystemResource("missing-train-schedule.xlsx"));

		// when & then
		assertThatThrownBy(parser::getSheets)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("파일을 읽을 수 없습니다");
	}
}
