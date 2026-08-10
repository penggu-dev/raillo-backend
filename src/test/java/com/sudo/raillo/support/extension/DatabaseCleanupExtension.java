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
		final List<String> truncateQueries = getTruncateQueries(jdbcTemplate);
		truncateTables(jdbcTemplate, truncateQueries);
	}

	private JdbcTemplate getJdbcTemplate(ExtensionContext context) {
		return SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
	}

	private List<String> getTruncateQueries(JdbcTemplate jdbcTemplate) {
		List<String> tableNames = jdbcTemplate.query(
			SELECT_TABLE_NAMES,
			(rs, rowNum) -> rs.getString("TABLE_NAME"));

		return tableNames.stream()
			.map(tableName -> "TRUNCATE TABLE `" + tableName + "`")
			.toList();
	}

	private void truncateTables(JdbcTemplate jdbcTemplate, List<String> truncateQueries) {
		try {
			execute(jdbcTemplate, "SET FOREIGN_KEY_CHECKS = FALSE");
			truncateQueries.forEach(query -> execute(jdbcTemplate, query));
		} finally {
			execute(jdbcTemplate, "SET FOREIGN_KEY_CHECKS = TRUE");
		}
	}

	private void execute(JdbcTemplate jdbcTemplate, String query) {
		jdbcTemplate.execute(query);
	}
}
