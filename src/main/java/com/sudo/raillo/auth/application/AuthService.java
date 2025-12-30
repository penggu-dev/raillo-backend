package com.sudo.raillo.auth.application;

import com.sudo.raillo.auth.application.dto.LogoutToken;
import com.sudo.raillo.auth.application.dto.request.LoginRequest;
import com.sudo.raillo.auth.application.dto.request.SignUpRequest;
import com.sudo.raillo.auth.application.dto.response.ReissueTokenResponse;
import com.sudo.raillo.auth.application.dto.response.SignUpResponse;
import com.sudo.raillo.auth.application.dto.response.TokenResponse;
import com.sudo.raillo.auth.exception.TokenError;
import com.sudo.raillo.auth.infrastructure.AuthRedisRepository;
import com.sudo.raillo.auth.security.jwt.TokenExtractor;
import com.sudo.raillo.auth.security.jwt.TokenGenerator;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.MemberNoGenerator;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final MemberNoGenerator memberNoGenerator;
	private final AuthenticationManager authenticationManager;
	private final TokenGenerator tokenGenerator;
	private final TokenExtractor tokenExtractor;
	private final AuthRedisRepository authRedisRepository;

	@Transactional
	public SignUpResponse signUp(SignUpRequest request) {

		if (memberRepository.existsByMemberDetailEmail(request.email())) {
			throw new BusinessException(MemberError.DUPLICATE_EMAIL);
		}

		String memberNo = memberNoGenerator.generateMemberNo();

		Member member = Member.create(
			request.name(),
			passwordEncoder.encode(request.password()),
			request.phoneNumber(),
			memberNo,
			request.email(),
			LocalDate.parse(request.birthDate(), DateTimeFormatter.ISO_LOCAL_DATE),
			request.gender()
		);
		memberRepository.save(member);

		return new SignUpResponse(memberNo);
	}

	public TokenResponse login(LoginRequest request) {

		UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
			request.memberNo(), request.password());

		Authentication authentication = authenticationManager.authenticate(authenticationToken);
		TokenResponse tokenResponse = tokenGenerator.generateTokenDTO(authentication);

		// 레디스에 리프레시 토큰 저장
		authRedisRepository.saveRefreshToken(request.memberNo(), tokenResponse.refreshToken());

		return tokenResponse;
	}

	public void logout(String accessToken, String memberNo) {

		// Redis 에서 해당 memberNo 로 저장된 RefreshToken 이 있는지 여부 확인 후, 존재할 경우 삭제
		if (authRedisRepository.getRefreshToken(memberNo) != null) {
			authRedisRepository.deleteRefreshToken(memberNo);
		}

		// 해당 AccessToken 유효 시간을 가져와 BlackList 에 저장
		Duration expiration = tokenExtractor.getAccessTokenExpiration(accessToken);
		LogoutToken logoutToken = new LogoutToken("logout", expiration);
		authRedisRepository.saveLogoutToken(accessToken, logoutToken, expiration);
	}

	public ReissueTokenResponse reissueAccessToken(String refreshToken) {

		String memberNo = tokenExtractor.getMemberNo(refreshToken);

		String restoredRefreshToken = authRedisRepository.getRefreshToken(memberNo);

		if (!refreshToken.equals(restoredRefreshToken)) {
			throw new BusinessException(TokenError.NOT_EQUALS_REFRESH_TOKEN);
		}

		return tokenGenerator.reissueAccessToken(refreshToken);
	}
}
