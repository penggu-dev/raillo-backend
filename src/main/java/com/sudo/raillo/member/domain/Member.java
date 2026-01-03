package com.sudo.raillo.member.domain;

import com.sudo.raillo.global.domain.BaseEntity;
import com.sudo.raillo.global.exception.error.DomainException;
import com.sudo.raillo.member.exception.MemberError;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE member SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
@Table(
	name = "member",
	indexes = {
		@Index(name = "idx_member_deleted_updated", columnList = "is_deleted,updated_at")
	}
)
public class Member extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false)
	private String phoneNumber;

	@Enumerated(EnumType.STRING)
	private Role role;

	@Embedded
	private MemberDetail memberDetail;

	@Column(name = "is_deleted", nullable = false)
	private boolean isDeleted = false;

	public static Member create(
		String name,
		String password,
		String phoneNumber,
		String memberNo,
		String email,
		LocalDate birthDate,
		String gender
	) {
		Member member = new Member();
		member.name = name;
		member.password = password;
		member.phoneNumber = phoneNumber;
		member.role = Role.MEMBER;
		member.memberDetail = MemberDetail.create(memberNo, email, birthDate, gender);
		return member;
	}

	// 비회원 등록 정적 팩토리 메서드
	public static Member createGuest(
		String name,
		String password,
		String phoneNumber
	) {
		Member member = new Member();
		member.name = name;
		member.password = password;
		member.phoneNumber = phoneNumber;
		member.role = Role.GUEST;
		return member;
	}

	public void updatePhoneNumber(String newPhoneNumber) {
		validateNewPhoneNumber(newPhoneNumber);
		this.phoneNumber = newPhoneNumber;
	}

	public void updatePassword(String newPassword) {
		validateNewPassword(newPassword);
		this.password = newPassword;
	}

	public void updateEmail(String newEmail) {
		validateNewEmail(newEmail);
		this.memberDetail.updateEmail(newEmail);
	}

	private void validateNewPhoneNumber(String newPhoneNumber) {
		if (phoneNumber.equals(newPhoneNumber)) {
			throw new DomainException(MemberError.SAME_PHONE_NUMBER);
		}
	}

	private void validateNewPassword(String newPassword) {
		if (password.equals(newPassword)) {
			throw new DomainException(MemberError.SAME_PASSWORD);
		}
	}

	private void validateNewEmail(String newEmail) {
		if (memberDetail.getEmail().equals(newEmail)) {
			throw new DomainException(MemberError.SAME_EMAIL);
		}
	}
}
