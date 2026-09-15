package com.sudo.raillo.support.extension;

import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class DatabaseCleanupExtension implements AfterEachCallback {

	private static final String SELECT_TABLE_NAMES = """
		SELECT TABLE_NAME
		FROM INFORMATION_SCHEMA.TABLES
		WHERE TABLE_SCHEMA = DATABASE()
		  AND TABLE_TYPE = 'BASE TABLE'
		""";

	@Override
	public void afterEach(ExtensionContext context) {
		JdbcTemplate jdbcTemplate = getJdbcTemplate(context);
		List<String> deleteQueries = getDeleteQueries(jdbcTemplate);
		deleteTables(jdbcTemplate, deleteQueries);
	}

	private JdbcTemplate getJdbcTemplate(ExtensionContext context) {
		return SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
	}

	private List<String> getDeleteQueries(JdbcTemplate jdbcTemplate) {
		List<String> tableNames = jdbcTemplate.query(
			SELECT_TABLE_NAMES,
			(rs, rowNum) -> rs.getString("TABLE_NAME"));

		return tableNames.stream()
			.map(tableName -> "DELETE FROM `" + tableName + "`")
			.toList();
	}

	private void deleteTables(JdbcTemplate jdbcTemplate, List<String> deleteQueries) {
		try {
			execute(jdbcTemplate, "SET FOREIGN_KEY_CHECKS = FALSE");
			// 테이블별로 개별 실행하지 않고 JDBC batch로 묶어 라운드트립을 줄인다.
			jdbcTemplate.batchUpdate(deleteQueries.toArray(String[]::new));
		} finally {
			execute(jdbcTemplate, "SET FOREIGN_KEY_CHECKS = TRUE");
		}
	}

	private void execute(JdbcTemplate jdbcTemplate, String query) {
		jdbcTemplate.execute(query);
	}
}
