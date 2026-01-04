package com.sudo.raillo.member.application;

import com.sudo.raillo.auth.application.EmailAuthService;
import com.sudo.raillo.auth.application.dto.response.SendCodeResponse;
import com.sudo.raillo.auth.application.dto.response.TemporaryTokenResponse;
import com.sudo.raillo.auth.exception.AuthError;
import com.sudo.raillo.auth.security.jwt.TokenGenerator;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.dto.response.VerifyMemberNoResponse;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRedisRepository;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberFindService {

	private final MemberRepository memberRepository;
	private final TokenGenerator tokenGenerator;
	private final EmailAuthService emailAuthService;
	private final MemberRedisRepository memberRedisRepository;

	/**
	 * 이메일 인증을 통한 회원 번호 찾기
	 * */
	public SendCodeResponse requestFindMemberNo(String name, String phoneNumber) {
		Member member = memberRepository.findMemberByNameAndPhoneNumber(name, phoneNumber)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();

		sendCodeAndSaveMemberNo(memberEmail, memberNo);

		return new SendCodeResponse(memberEmail);
	}

	public VerifyMemberNoResponse verifyFindMemberNo(String email, String authCode) {
		String memberNo = verifyCodeAndGetMemberNo(email, authCode);

		return new VerifyMemberNoResponse(memberNo);
	}

	/**
	 * 이메일 인증을 통한 비밀번호 찾기
	 * */
	public SendCodeResponse requestFindPassword(String name, String memberNo) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		if (!member.getName().equals(name)) {
			throw new BusinessException(MemberError.NAME_MISMATCH);
		}

		String memberEmail = member.getMemberDetail().getEmail();
		sendCodeAndSaveMemberNo(memberEmail, memberNo);

		return new SendCodeResponse(memberEmail);
	}

	public TemporaryTokenResponse verifyFindPassword(String email, String authCode) {
		String memberNo = verifyCodeAndGetMemberNo(email, authCode);
		String temporaryToken = tokenGenerator.generateTemporaryToken(memberNo);

		return new TemporaryTokenResponse(temporaryToken);
	}

	private String verifyCodeAndGetMemberNo(String email, String authCode) {
		boolean isVerified = emailAuthService.verifyAuthCode(email, authCode);

		if (!isVerified) { // 인증 실패 시
			throw new BusinessException(AuthError.INVALID_AUTH_CODE);
		}

		String memberNo = memberRedisRepository.getMemberNo(email);
		memberRedisRepository.deleteMemberNo(email);

		return memberNo;
	}

	private void sendCodeAndSaveMemberNo(String email, String memberNo) {
		memberRedisRepository.saveMemberNo(email, memberNo); // 레디스에 이메일 검증 후 보낼 회원번호 저장
		emailAuthService.sendAuthCode(email); // 찾아온 이메일로 인증 코드 전송
	}
}
