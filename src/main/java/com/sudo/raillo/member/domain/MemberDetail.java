package com.sudo.raillo.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberDetail {

	@Column(unique = true)
	private String memberNo;

	@Column(unique = true)
	private String email;

	private LocalDate birthDate;

	@Column(length = 1)
	private String gender;

	public static MemberDetail create(String memberNo, String email, LocalDate birthDate,
		String gender) {
		return new MemberDetail(memberNo, email, birthDate, gender);
	}

	public void updateEmail(String newEmail) {
		this.email = newEmail;
	}

}
