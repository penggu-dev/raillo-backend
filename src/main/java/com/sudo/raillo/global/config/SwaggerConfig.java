package com.sudo.raillo.global.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

@Configuration
public class SwaggerConfig {
	private static final String JWT_SCHEME_NAME = "JWT";
	private static final String BEARER_FORMAT = "JWT";
	private static final String SCHEME = "bearer";

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
			.components(new Components()
				.addSecuritySchemes(JWT_SCHEME_NAME, createJWTSecurityScheme())
			)
			.info(createApiInfo())
			.servers(createServers())
			.tags(createTags());
	}

	/**
	 * API 기본 정보 설정
	 */
	private Info createApiInfo() {
		return new Info()
			.title("Raillo Backend API")
			.description("Raillo (코레일 클론코딩) REST API 문서 - 구름 백엔드 개발자 과정 3기 1차 팀프로젝트")
			.version("v1.0.0")
			.contact(new Contact()
				.name("Sudo Raillo Team")
				.email("kdasunb6@gmail.com")
				.url("https://github.com/goorm-sudo")
			)
			.license(new License()
				.name("MIT License")
				.url("https://opensource.org/licenses/MIT")
			);
	}

	/**
	 * JWT 보안 스키마 설정
	 */
	private SecurityScheme createJWTSecurityScheme() {
		return new SecurityScheme()
			.name(JWT_SCHEME_NAME)
			.type(SecurityScheme.Type.HTTP)
			.scheme(SCHEME)
			.bearerFormat(BEARER_FORMAT)
			.description("JWT 토큰을 입력하세요 (Bearer 제외)");
	}

	/**
	 * API 서버 정보 설정
	 */
	private List<Server> createServers() {
		return Arrays.asList(
			new Server()
				.url("http://localhost:8080")
				.description("로컬 개발 서버"),
			new Server()
				.url("https://dev-api.raillo.com")
				.description("개발 서버"),
			new Server()
				.url("https://api.raillo.com")
				.description("운영 서버")
		);
	}

	/**
	 * API 태그 그룹 설정
	 */
	private List<Tag> createTags() {
		return Arrays.asList(
			new Tag()
				.name("Authentication")
				.description("🔐 인증 API - 회원 로그인, 회원가입, 토큰 관리 API"),
			new Tag()
				.name("Members")
				.description("👤 회원 API - 회원 정보 조회, 수정, 탈퇴, 관리 API"),
			new Tag()
				.name("Trains")
				.description("🚄 열차 조회 API - 열차 스케줄, 객차, 좌석 정보 조회 API"),
			new Tag()
				.name("Tickets")
				.description("🚝 승차권 API - 승차권 조회, 발권, 취소 API"),
			new Tag()
				.name("Reservations")
				.description("🎫 예매 API - 예매 생성, 조회, 취소, 결제 API"),
			new Tag()
				.name("CartReservations")
				.description("장바구니 API - 예약 등록, 조회 API"),
			new Tag()
				.name("Payments")
				.description("💳 결제 API - 결제 처리, 환불, 결제 내역 API"),
			new Tag()
				.name("Statistics")
				.description("📊 통계 API - 이용 통계, 매출 통계 API"),
			new Tag()
				.name("Dev")
				.description("🔧 개발 도구 - 개발용 테스트 API (개발 환경 전용)")
		);
	}
}
