package com.sudo.raillo.batch.member.infrastructure.jdbc;

import java.util.List;

public interface MemberJdbcRepository {

	void deleteAllByIds(List<Long> memberIds);
}
