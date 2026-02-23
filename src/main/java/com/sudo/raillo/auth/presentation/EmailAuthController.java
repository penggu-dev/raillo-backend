package com.sudo.raillo.auth.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.auth.application.EmailAuthService;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.auth.application.dto.request.SendCodeRequest;
import com.sudo.raillo.auth.application.dto.request.VerifyCodeRequest;
import com.sudo.raillo.auth.application.dto.response.SendCodeResponse;
import com.sudo.raillo.auth.application.dto.response.VerifyCodeResponse;
import com.sudo.raillo.auth.docs.EmailAuthControllerDoc;
import com.sudo.raillo.auth.success.AuthSuccess;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class EmailAuthController implements EmailAuthControllerDoc {

	private final EmailAuthService emailAuthService;
	private final MemberService memberService;

	/**
	 * 이메일 코드 전송 - 인증되지 않은 사용자
	 * */
	@PostMapping("/emails")
	public SuccessResponse<SendCodeResponse> sendAuthCode(@RequestBody @Valid SendCodeRequest request) {
		SendCodeResponse response = emailAuthService.sendAuthCode(request.email());

		return SuccessResponse.of(AuthSuccess.SEND_CODE_SUCCESS, response);
	}

	/**
	 * 이메일 코드 전송 - 인증된 사용자
	 * */
	@PostMapping("/members/emails")
	public SuccessResponse<SendCodeResponse> sendAuthCodeWithMember(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		log.info("[이메일 코드 인증 시도] memberNo={}", userDetails.getUsername());

		String email = memberService.getMemberEmail(userDetails.getUsername());
		SendCodeResponse response = emailAuthService.sendAuthCode(email);

		return SuccessResponse.of(AuthSuccess.SEND_CODE_SUCCESS, response);
	}

	/**
	 * 이메일 코드 검증 - 인증된 사용자, 인증되지 않은 사용자 모두 사용
	 * */
	@PostMapping("/emails/verify")
	public SuccessResponse<VerifyCodeResponse> verifyAuthCode(@RequestBody @Valid VerifyCodeRequest request) {
		boolean isVerified = emailAuthService.verifyAuthCode(request.email(), request.authCode());
		VerifyCodeResponse response = new VerifyCodeResponse(isVerified);

		return SuccessResponse.of(AuthSuccess.VERIFY_CODE_SUCCESS_FINISH, response);
	}
}
