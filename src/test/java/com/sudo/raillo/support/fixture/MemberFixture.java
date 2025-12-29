package com.sudo.raillo.support.fixture;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.domain.MemberDetail;
import com.sudo.raillo.member.domain.Role;
import java.time.LocalDate;
import lombok.Getter;

@Getter
public enum MemberFixture {

	MEMBER(
		"202507300001",
		"test@example.com",
		LocalDate.of(2000, 1, 1),
		"M",
		"member",
		"010-1111-1111",
		"testPassword",
		Role.MEMBER
	),
	OTHER_MEMBER(
		"202507300002",
		"other@example.com",
		LocalDate.of(2000, 1, 1),
		"W",
		"other",
		"010-2222-2222",
		"otherPassword",
		Role.MEMBER
	);

	private final String memberNo;
	private final String email;
	private final LocalDate birthDate;
	private final String gender;
	private final String name;
	private final String phoneNumber;
	private final String password;
	private final Role role;

	MemberFixture(String memberNo, String email, LocalDate birthDate, String gender,
		String name, String phoneNumber, String password, Role role) {
		this.memberNo = memberNo;
		this.email = email;
		this.birthDate = birthDate;
		this.gender = gender;
		this.name = name;
		this.phoneNumber = phoneNumber;
		this.password = password;
		this.role = role;
	}

	public static Member createStandardMember() {
		MemberDetail memberDetail = MemberDetail.create(MEMBER.memberNo, MEMBER.email,
			MEMBER.birthDate, MEMBER.gender);
		return Member.create(MEMBER.name, MEMBER.phoneNumber, MEMBER.password, MEMBER.role, memberDetail);
	}

	public static Member createOtherMember() {
		MemberDetail memberDetail = MemberDetail.create(OTHER_MEMBER.memberNo,
			OTHER_MEMBER.email, OTHER_MEMBER.birthDate, OTHER_MEMBER.gender);
		return Member.create(OTHER_MEMBER.name, OTHER_MEMBER.phoneNumber, OTHER_MEMBER.password, OTHER_MEMBER.role,
			memberDetail);
	}
}
