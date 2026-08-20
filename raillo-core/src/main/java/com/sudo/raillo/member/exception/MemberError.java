package com.sudo.raillo.member.exception;

import org.springframework.http.HttpStatus;

import com.sudo.raillo.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberError implements ErrorCode {

	USER_NOT_FOUND("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, "MEMBER_001"),
	DUPLICATE_EMAIL("이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT, "MEMBER_002"),
	INVALID_PASSWORD("비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED, "MEMBER_003"),
	MEMBER_DELETE_FAIL("회원 삭제에 실패하였습니다.", HttpStatus.INTERNAL_SERVER_ERROR, "MEMBER_004"),
	SAME_PHONE_NUMBER("현재 사용하는 휴대폰 번호와 동일합니다.", HttpStatus.CONFLICT, "MEMBER_005"),
	DUPLICATE_PHONE_NUMBER("이미 사용 중인 휴대폰 번호입니다.", HttpStatus.CONFLICT, "MEMBER_006"),
	SAME_EMAIL("현재 사용중인 이메일과 동일합니다.", HttpStatus.CONFLICT, "MEMBER_007"),
	SAME_PASSWORD("현재 사용중인 비밀번호와 동일합니다.", HttpStatus.CONFLICT, "MEMBER_008"),
	NAME_MISMATCH("이름이 일치하지 않습니다.", HttpStatus.BAD_REQUEST, "MEMBER_009"),
	EMAIL_UPDATE_ALREADY_REQUESTED("해당 이메일에 대한 변경 요청이 이미 처리중입니다.", HttpStatus.CONFLICT, "MEMBER_010");

	private final String message;
	private final HttpStatus status;
	private final String code;
}
