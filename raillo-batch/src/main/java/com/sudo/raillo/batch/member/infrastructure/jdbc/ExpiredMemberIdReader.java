package com.sudo.raillo.batch.member.infrastructure.jdbc;

import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.support.MySqlPagingQueryProvider;

/**
 * 삭제 상태이면서 기준 시각 이전에 삭제된 회원 ID를 id 오름차순으로 페이징 조회한다.
 */
public class ExpiredMemberIdReader extends JdbcPagingItemReader<Long> {

	public ExpiredMemberIdReader(DataSource dataSource, LocalDateTime deletedBefore, int pageSize) {
		super(dataSource, expiredMemberQueryProvider());
		setName("expiredMemberIdReader");
		setParameterValues(Map.of("deletedBefore", deletedBefore));
		setRowMapper((rs, rowNum) -> rs.getLong("id"));
		setPageSize(pageSize);
	}

	private static MySqlPagingQueryProvider expiredMemberQueryProvider() {
		MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
		queryProvider.setSelectClause("m.id");
		queryProvider.setFromClause("member m");
		queryProvider.setWhereClause("m.is_deleted = true AND m.updated_at < :deletedBefore");
		queryProvider.setSortKeys(Map.of("id", Order.ASCENDING));
		return queryProvider;
	}
}
