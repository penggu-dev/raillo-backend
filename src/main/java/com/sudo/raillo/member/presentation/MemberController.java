package com.sudo.raillo.member.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.auth.security.jwt.TokenExtractor;
import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.member.application.MemberService;
import com.sudo.raillo.member.application.dto.response.MemberInfoResponse;
import com.sudo.raillo.member.docs.MemberControllerDoc;
import com.sudo.raillo.member.success.MemberSuccess;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MemberController implements MemberControllerDoc {

	private final MemberService memberService;
	private final TokenExtractor tokenExtractor;

	@DeleteMapping("/members")
	public SuccessResponse<?> memberDelete(
		HttpServletRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String accessToken = tokenExtractor.resolveToken(request);
		memberService.memberDelete(accessToken, userDetails.getUsername());

		return SuccessResponse.of(MemberSuccess.MEMBER_DELETE_SUCCESS);
	}

	@GetMapping("/members/me")
	public SuccessResponse<MemberInfoResponse> getMemberInfo(@AuthenticationPrincipal UserDetails userDetails) {
		MemberInfoResponse response = memberService.getMemberInfo(userDetails.getUsername());

		return SuccessResponse.of(MemberSuccess.MEMBER_INFO_SUCCESS, response);
	}
}
