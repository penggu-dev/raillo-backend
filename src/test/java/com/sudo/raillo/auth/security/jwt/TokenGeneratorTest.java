package com.sudo.raillo.auth.security.jwt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.core.Authentication;

import com.sudo.raillo.auth.exception.TokenError;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.support.annotation.ServiceTest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@ServiceTest
class TokenGeneratorTest {

	private static final String TEST_SECRET_KEY = "crailotestjwtsecretkey2025fordevelopmentandtestingonlylonglonglonglonglonglonglonglonglong==";

	@Mock
	private Authentication authentication;

	private TokenValidator tokenValidator;

	private TokenGenerator tokenGenerator;

	private SecretKey testKey;

	@BeforeEach
	void setUp() {
		byte[] keyBytes = Decoders.BASE64.decode(TEST_SECRET_KEY);
		testKey = Keys.hmacShaKeyFor(keyBytes);
		tokenValidator = new TokenValidator(TEST_SECRET_KEY);
		TokenExtractor tokenExtractor = new TokenExtractor(TEST_SECRET_KEY);
		tokenGenerator = new TokenGenerator(TEST_SECRET_KEY, tokenValidator, tokenExtractor);
	}

	@Test
	@DisplayName("Authentication에 있는 회원 정보로 토큰을 발급한다.")
	void generateTokens() {
		// given
		String memberNo = "test";
		when(authentication.getName()).thenReturn(memberNo);

		// when
		long beforeTime = System.currentTimeMillis();
		var result = tokenGenerator.generateTokenDTO(authentication);
		long afterTime = System.currentTimeMillis();

		// then
		assertThat(result.grantType()).isEqualTo("Bearer");
		tokenValidator.validateToken(result.accessToken());
		tokenValidator.validateToken(result.refreshToken());
		assertThat(result.accessTokenExpiresIn()).isBetween(beforeTime + (30 * 60 * 1000),
			afterTime + (30 * 60 * 1000));
	}

	@Test
	@DisplayName("유효한 refreshToken 으로 accessToken을 재발급한다.")
	void refreshAccessToken() {
		// given
		String memberNo = "test";
		when(authentication.getName()).thenReturn(memberNo);
		var tokenResponse = tokenGenerator.generateTokenDTO(authentication);

		// when
		long beforeTime = System.currentTimeMillis();
		var result = tokenGenerator.reissueAccessToken(tokenResponse.refreshToken());
		long afterTime = System.currentTimeMillis();

		// then
		tokenValidator.validateToken(result.accessToken());
		assertThat(result.accessTokenExpiresIn()).isBetween(beforeTime + (30 * 60 * 1000),
			afterTime + (30 * 60 * 1000));
	}

	@Test
	@DisplayName("회원 정보로 TemporaryToken을 발급한다.")
	void generateTemporaryToken() {
		// given
		String memberNo = "test";

		// when
		String temporaryToken = tokenGenerator.generateTemporaryToken(memberNo);

		// then
		tokenValidator.validateToken(temporaryToken);
	}

	@Test
	@DisplayName("만료된 refreshToken 으로 accessToken 재발급을 시도하면 예외가 발생한다.")
	void shouldThrowExceptionWhenReissuingAccessTokenWithInvalidRefreshToken() {
		// given
		String memberNo = "test";
		String authorities = "ROLE_MEMBER";
		String invalidRefreshToken = Jwts.builder()
			.setSubject(memberNo)
			.claim("auth", authorities)
			.claim("isRefreshToken", true)
			.setExpiration(new Date(System.currentTimeMillis() - 1000))
			.signWith(testKey, SignatureAlgorithm.HS512)
			.compact();

		// when & then
		assertThatThrownBy(() -> tokenGenerator.reissueAccessToken(invalidRefreshToken))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TokenError.INVALID_REFRESH_TOKEN.getMessage());
	}

	@Test
	@DisplayName("isRefreshToken 클레임이 없는 refreshToken 으로 accessToken 재발급을 시도하면 예외가 발생한다.")
	void shouldThrowExceptionWhenReissuingAccessTokenWithoutRefreshTokenClaim() {
		// given
		String memberNo = "test";
		String authorities = "ROLE_MEMBER";
		String tokenWithoutClaim = Jwts.builder()
			.setSubject(memberNo)
			.claim("auth", authorities)
			.setExpiration(new Date(System.currentTimeMillis() + 1000 * 60))
			.signWith(testKey, SignatureAlgorithm.HS512)
			.compact();

		// when & then
		assertThatThrownBy(() -> tokenGenerator.reissueAccessToken(tokenWithoutClaim))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TokenError.INVALID_REFRESH_TOKEN.getMessage());
	}

	@Test
	@DisplayName("isRefreshToken 클레임이 false인 refreshToken 으로 accessToken 재발급을 시도하면 예외가 발생한다.")
	void shouldThrowExceptionWhenReissuingAccessTokenWithFalseRefreshTokenClaim() {
		// given
		String memberNo = "test";
		String authorities = "ROLE_MEMBER";
		String tokenWithFalseClaim = Jwts.builder()
			.setSubject(memberNo)
			.claim("auth", authorities)
			.claim("isRefreshToken", false)
			.setExpiration(new Date(System.currentTimeMillis() + 1000 * 60))
			.signWith(testKey, SignatureAlgorithm.HS512)
			.compact();

		// when & then
		assertThatThrownBy(() -> tokenGenerator.reissueAccessToken(tokenWithFalseClaim))
			.isInstanceOf(BusinessException.class)
			.hasMessage(TokenError.INVALID_REFRESH_TOKEN.getMessage());
	}
}
