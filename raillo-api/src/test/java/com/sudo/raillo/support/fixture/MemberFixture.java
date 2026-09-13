package com.sudo.raillo.support.fixture;

import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.domain.Role;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberFixture {

	private String memberNo = "202507300001";
	private String email = "test@example.com";
	private LocalDate birthDate = LocalDate.of(2000, 1, 1);
	private String gender = "M";
	private String name = "member";
	private String phoneNumber = "010-1111-1111";
	private String password = "testPassword";
	private Role role = Role.MEMBER;

	public static Member create() {
		return builder().build();
	}

	public static Member createOther() {
		return builder()
			.withMemberNo("202507300002")
			.withEmail("other@example.com")
			.withGender("W")
			.withName("other")
			.withPhoneNumber("010-2222-2222")
			.withPassword("otherPassword")
			.build();
	}

	// builder method
	public static MemberFixture builder() {
		return new MemberFixture();
	}

	public Member build() {
		return Member.create(name, password, phoneNumber, memberNo, email, birthDate, gender);
	}

	public MemberFixture withMemberNo(String memberNo) {
		this.memberNo = memberNo;
		return this;
	}

	public MemberFixture withEmail(String email) {
		this.email = email;
		return this;
	}

	public MemberFixture withBirthDate(LocalDate birthDate) {
		this.birthDate = birthDate;
		return this;
	}

	public MemberFixture withGender(String gender) {
		this.gender = gender;
		return this;
	}

	public MemberFixture withName(String name) {
		this.name = name;
		return this;
	}

	public MemberFixture withPhoneNumber(String phoneNumber) {
		this.phoneNumber = phoneNumber;
		return this;
	}

	public MemberFixture withPassword(String password) {
		this.password = password;
		return this;
	}

	public MemberFixture withRole(Role role) {
		this.role = role;
		return this;
	}
}
