package com.sudo.raillo.global.config;

import static org.springframework.security.config.http.SessionCreationPolicy.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.sudo.raillo.auth.infrastructure.AuthRedisRepository;
import com.sudo.raillo.auth.security.jwt.JwtAccessDeniedHandler;
import com.sudo.raillo.auth.security.jwt.JwtAuthenticationEntryPoint;
import com.sudo.raillo.auth.security.jwt.JwtFilter;
import com.sudo.raillo.auth.security.jwt.TokenExtractor;
import com.sudo.raillo.auth.security.jwt.TokenValidator;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private final AuthRedisRepository authRedisRepository;
	private final TokenExtractor tokenExtractor;
	private final TokenValidator tokenValidator;
	private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

	@Bean
	public BCryptPasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	// 아이디와 패스워드의 일치 여부를 확인할 때 사용하는 객체
	@Bean
	public AuthenticationManager authenticationManagerBean(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
		CorsConfigurationSource corsConfigurationSource) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.exceptionHandling(exception -> {
				exception.authenticationEntryPoint(jwtAuthenticationEntryPoint); // 권한 확인
				exception.accessDeniedHandler(jwtAccessDeniedHandler); // 인증된 접근 확인
			})
			.sessionManagement(session -> session.sessionCreationPolicy(STATELESS))
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			// HTTP 요청에 대한 접근 권한 설정
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers("/", "/auth/signup", "/auth/login", "/auth/reissue").permitAll()
					.requestMatchers(HttpMethod.POST, "/auth/emails/**").permitAll()
					.requestMatchers(HttpMethod.POST, "/auth/member-no/**", "/auth/password/**").permitAll()
					.requestMatchers("/api/v1/guest/register", "/api/v1/trains/**").permitAll()
					.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
					.requestMatchers("/actuator/**", "/health").permitAll()
					.requestMatchers("/test/payments/**").permitAll()
					.anyRequest().authenticated();
			})
			.addFilterBefore(new JwtFilter(tokenExtractor, tokenValidator, authRedisRepository),
				UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
