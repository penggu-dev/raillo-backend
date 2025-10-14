package com.sudo.raillo.member.infrastructure;

import java.util.Optional;

import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;

public interface MemberRepositoryCustom {
	Optional<Member> findByMemberNo(String memberNo);

	Optional<Member> findMemberByNameAndPhoneNumber(String name, String phoneNumber);

	default Member getMember(String memberNo) {
		return findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}
}
