package com.sudo.raillo.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberDetail {

	@Column(unique = true)
	private String memberNo;

	@Column(unique = true)
	private String email;

	private LocalDate birthDate;

	@Column(length = 1)
	private String gender;

	protected static MemberDetail create(
		String memberNo,
		String email,
		LocalDate birthDate,
		String gender
	) {
		MemberDetail memberDetail = new MemberDetail();
		memberDetail.memberNo = memberNo;
		memberDetail.email = email;
		memberDetail.birthDate = birthDate;
		memberDetail.gender = gender;
		return memberDetail;
	}

	protected void updateEmail(String email) {
		this.email = email;
	}
}
