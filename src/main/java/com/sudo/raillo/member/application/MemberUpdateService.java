package com.sudo.raillo.member.application;

import com.sudo.raillo.auth.application.EmailAuthService;
import com.sudo.raillo.auth.application.dto.request.SendCodeRequest;
import com.sudo.raillo.auth.application.dto.response.SendCodeResponse;
import com.sudo.raillo.auth.exception.AuthError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.dto.request.UpdateEmailRequest;
import com.sudo.raillo.member.application.dto.request.UpdatePasswordRequest;
import com.sudo.raillo.member.application.dto.request.UpdatePhoneNumberRequest;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRedisRepository;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberUpdateService {

	private final MemberRepository memberRepository;
	private final EmailAuthService emailAuthService;
	private final PasswordEncoder passwordEncoder;
	private final MemberRedisRepository memberRedisRepository;

	/**
	 * 이메일 인증을 통한 이메일 변경
	 * */
	@Transactional(readOnly = true)
	public SendCodeResponse requestUpdateEmail(SendCodeRequest request, String memberNo) {
		Member member = getMember(memberNo);

		// 이미 본인 이메일이랑 동일한 이메일로 변경을 요청했을 경우 예외
		if (member.getMemberDetail().getEmail().equals(request.email())) {
			throw new BusinessException(MemberError.SAME_EMAIL);
		}

		// 다른 회원이 사용중인 이메일을 입력했을 경우 예외
		if (memberRepository.existsByMemberDetailEmail(request.email())) {
			throw new BusinessException(MemberError.DUPLICATE_EMAIL);
		}

		// 동일 요청 건이 없으면 같은 이메일에 대한 요청이 들어오지 못하도록 redis 에 등록
		if (!memberRedisRepository.handleUpdateEmailRequest(request.email())) {
			throw new BusinessException(MemberError.EMAIL_UPDATE_ALREADY_REQUESTED);
		}

		return emailAuthService.sendAuthCode(request.email());
	}

	public void verifyUpdateEmail(UpdateEmailRequest request, String memberNo) {
		Member member = getMember(memberNo);

		// 이메일 검증
		if (!emailAuthService.verifyAuthCode(request.newEmail(), request.authCode())) {
			throw new BusinessException(AuthError.INVALID_AUTH_CODE);
		}

		member.updateEmail(request.newEmail());
		memberRedisRepository.deleteUpdateEmailRequest(request.newEmail()); // 해당 변경 요청 건 redis 에서 삭제
	}

	/**
	 * 회원 휴대폰 번호 변경
	 * */
	public void updatePhoneNumber(UpdatePhoneNumberRequest request, String memberNo) {
		Member member = getMember(memberNo);

		// 다른 회원이 사용 중인 번호를 입력했을 경우 예외
		if (memberRepository.existsByPhoneNumberAndIdNot(request.newPhoneNumber(), member.getId())) {
			throw new BusinessException(MemberError.DUPLICATE_PHONE_NUMBER);
		}

		member.updatePhoneNumber(request.newPhoneNumber());
	}

	/**
	 * 회원 비밀번호 변경
	 * */
	public void updatePassword(UpdatePasswordRequest request, String memberNo) {
		Member member = getMember(memberNo);
		member.updatePassword(passwordEncoder.encode(request.newPassword()));
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}
}
