package com.sudo.raillo.batch.member.infrastructure.jdbc;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberJdbcRepositoryImpl implements MemberJdbcRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void deleteAllByIds(List<Long> memberIds) {
		String sql = "DELETE FROM member WHERE id = ?";

		jdbcTemplate.batchUpdate(sql, memberIds, memberIds.size(),
			(ps, memberId) -> ps.setLong(1, memberId));
	}
}
