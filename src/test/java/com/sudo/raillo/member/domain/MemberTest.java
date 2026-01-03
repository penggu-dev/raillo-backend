package com.sudo.raillo.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.support.fixture.MemberFixture;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberTest {

	@Test
	@DisplayName("회원 생성 시 역할이 MEMBER이고 회원 상세 정보가 설정된다")
	void create() {
		// given
		String name = "홍길동";
		String password = "password123";
		String phoneNumber = "010-1234-5678";
		String memberNo = "202501010001";
		String email = "test@example.com";
		LocalDate birthDate = LocalDate.of(1990, 1, 1);
		String gender = "M";

		// when
		Member member = Member.create(name, password, phoneNumber, memberNo, email, birthDate, gender);

		// then
		assertThat(member.getName()).isEqualTo(name);
		assertThat(member.getPassword()).isEqualTo(password);
		assertThat(member.getPhoneNumber()).isEqualTo(phoneNumber);
		assertThat(member.getRole()).isEqualTo(Role.MEMBER);
		assertThat(member.getMemberDetail()).isNotNull();
		assertThat(member.getMemberDetail().getMemberNo()).isEqualTo(memberNo);
		assertThat(member.getMemberDetail().getEmail()).isEqualTo(email);
		assertThat(member.getMemberDetail().getBirthDate()).isEqualTo(birthDate);
		assertThat(member.getMemberDetail().getGender()).isEqualTo(gender);
	}

	@Test
	@DisplayName("비회원 생성 시 역할이 GUEST이고 회원 상세 정보가 없다")
	void createGuest() {
		// given
		String name = "비회원";
		String password = "guestPassword";
		String phoneNumber = "010-9999-9999";

		// when
		Member guest = Member.createGuest(name, password, phoneNumber);

		// then
		assertThat(guest.getName()).isEqualTo(name);
		assertThat(guest.getPassword()).isEqualTo(password);
		assertThat(guest.getPhoneNumber()).isEqualTo(phoneNumber);
		assertThat(guest.getRole()).isEqualTo(Role.GUEST);
		assertThat(guest.getMemberDetail()).isNull();
	}

	@Test
	@DisplayName("전화번호를 새로운 번호로 변경할 수 있다")
	void updatePhoneNumber() {
		// given
		Member member = MemberFixture.create();
		String newPhoneNumber = "010-9999-8888";

		// when
		member.updatePhoneNumber(newPhoneNumber);

		// then
		assertThat(member.getPhoneNumber()).isEqualTo(newPhoneNumber);
	}

	@Test
	@DisplayName("현재와 동일한 전화번호로 변경하면 예외가 발생한다")
	void updatePhoneNumberFail() {
		// given
		Member member = MemberFixture.builder()
			.withPhoneNumber("010-1111-1111")
			.build();
		String samePhoneNumber = "010-1111-1111";

		// when & then
		assertThatThrownBy(() -> member.updatePhoneNumber(samePhoneNumber))
			.isInstanceOf(DomainException.class)
			.hasMessage(MemberError.SAME_PHONE_NUMBER.getMessage());
	}

	@Test
	@DisplayName("비밀번호를 새로운 비밀번호로 변경할 수 있다")
	void updatePassword() {
		// given
		Member member = MemberFixture.create();
		String newPassword = "newPassword123";

		// when
		member.updatePassword(newPassword);

		// then
		assertThat(member.getPassword()).isEqualTo(newPassword);
	}

	@Test
	@DisplayName("현재와 동일한 비밀번호로 변경하면 예외가 발생한다")
	void updatePasswordFail() {
		// given
		Member member = MemberFixture.builder()
			.withPassword("samePassword")
			.build();
		String samePassword = "samePassword";

		// when & then
		assertThatThrownBy(() -> member.updatePassword(samePassword))
			.isInstanceOf(DomainException.class)
			.hasMessage(MemberError.SAME_PASSWORD.getMessage());
	}

	@Test
	@DisplayName("이메일을 새로운 이메일로 변경할 수 있다")
	void updateEmail() {
		// given
		Member member = MemberFixture.create();
		String newEmail = "new@example.com";

		// when
		member.updateEmail(newEmail);

		// then
		assertThat(member.getMemberDetail().getEmail()).isEqualTo(newEmail);
	}

	@Test
	@DisplayName("현재와 동일한 이메일로 변경하면 예외가 발생한다")
	void updateEmailFail() {
		// given
		Member member = MemberFixture.builder()
			.withEmail("same@example.com")
			.build();
		String sameEmail = "same@example.com";

		// when & then
		assertThatThrownBy(() -> member.updateEmail(sameEmail))
			.isInstanceOf(DomainException.class)
			.hasMessage(MemberError.SAME_EMAIL.getMessage());
	}
}