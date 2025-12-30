package com.sudo.raillo.member.domain;

import java.time.LocalDate;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import com.sudo.raillo.global.domain.BaseEntity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

	public void updatePhoneNumber(String phoneNumber) {
		this.phoneNumber = phoneNumber;
	}

	public void updatePassword(String password) {
		this.password = password;
	}

	public void updateEmail(String email) {
		this.memberDetail.updateEmail(email);
	}
}
