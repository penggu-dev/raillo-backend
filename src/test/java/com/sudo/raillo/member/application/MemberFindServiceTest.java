package com.sudo.raillo.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.sudo.raillo.auth.application.dto.response.SendCodeResponse;
import com.sudo.raillo.auth.application.dto.response.TemporaryTokenResponse;
import com.sudo.raillo.auth.exception.AuthError;
import com.sudo.raillo.auth.infrastructure.AuthRedisRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.application.dto.response.VerifyMemberNoResponse;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRedisRepository;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import com.sudo.raillo.support.annotation.ServiceTest;
import com.sudo.raillo.support.fixture.MemberFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@ServiceTest
class MemberFindServiceTest {

	@RegisterExtension
	static GreenMailExtension greenMail = new GreenMailExtension(
		new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP) // 포트 충돌이 있어 동적 포트 할당
	)
		.withConfiguration(GreenMailConfiguration.aConfig().withUser("testUser", "testPassword"))
		.withPerMethodLifecycle(true);

	@Autowired
	private JavaMailSenderImpl mailSender;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private MemberFindService memberFindService;

	@Autowired
	private MemberRedisRepository memberRedisRepository;

	@Autowired
	private AuthRedisRepository authRedisRepository;

	private Member member;

	@BeforeEach
	void setUp() {
		greenMail.start();
		mailSender.setPort(greenMail.getSmtp().getPort());

		member = MemberFixture.create();
		memberRepository.save(member);
	}

	@AfterEach
	void tearDown() {
		greenMail.stop();
	}

	@Test
	@DisplayName("존재하는 회원 정보로 이메일 인증을 통한 회원번호 찾기 요청에 성공한다.")
	void requestFindMemberNo_success() {
		//given
		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();

		//when
		SendCodeResponse response = memberFindService.requestFindMemberNo(member.getName(), member.getPhoneNumber());

		//then
		assertThat(response).isNotNull();
		assertThat(response.email()).isEqualTo(memberEmail);

		assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();

		String savedMemberNo = memberRedisRepository.getMemberNo(memberEmail);
		assertThat(savedMemberNo).isEqualTo(memberNo);

		String mailContent = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
		String authCode = authRedisRepository.getAuthCode(memberEmail);
		assertThat(mailContent).contains(authCode);
	}

	@Test
	@DisplayName("존재하지 않는 회원의 정보로 회원 번호를 찾으면 요청에 실패한다.")
	void requestFindMemberNo_fail() {
		//given
		String nonExistMemberName = "NonExistMemberName";

		//when & then
		assertThatExceptionOfType(BusinessException.class)
			.isThrownBy(() -> memberFindService.requestFindMemberNo(nonExistMemberName, member.getPhoneNumber()))
			.satisfies(exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MemberError.USER_NOT_FOUND));
	}

	@Test
	@DisplayName("올바른 인증 코드로 회원 번호 검증 요청에 성공한다.")
	void verifyAuthCode_success() {
		//given
		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();
		String authCode = "123456";

		memberRedisRepository.saveMemberNo(memberEmail, memberNo);
		authRedisRepository.saveAuthCode(memberEmail, authCode);

		//when
		VerifyMemberNoResponse response = memberFindService.verifyFindMemberNo(memberEmail, authCode);

		//then
		assertThat(response).isNotNull();
		assertThat(response.memberNo()).isEqualTo(memberNo);

		// 검증 성공 후 레디스에 저장된 회원 번호와 인증 코드 삭제 되었는지 확인
		String savedMemberNo = memberRedisRepository.getMemberNo(memberEmail);
		assertThat(savedMemberNo).isNull();
		String savedAuthCode = authRedisRepository.getAuthCode(memberEmail);
		assertThat(savedAuthCode).isNull();
	}

	@Test
	@DisplayName("인증 코드가 일치하지 않으면 회원번호 검증 요청에 실패한다.")
	void verifyAuthCode_fail() {
		//given
		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();
		String correctAuthCode = "123456";
		String wrongAuthCode = "111111";

		memberRedisRepository.saveMemberNo(memberEmail, memberNo);
		authRedisRepository.saveAuthCode(memberEmail, correctAuthCode);

		//when & then
		assertThatExceptionOfType(BusinessException.class)
			.isThrownBy(() -> memberFindService.verifyFindMemberNo(memberEmail, wrongAuthCode))
			.satisfies(exception ->
				assertThat(exception.getErrorCode()).isEqualTo(AuthError.INVALID_AUTH_CODE));
	}

	@Test
	@DisplayName("존재하는 회원 정보로 이메일 인증을 통한 비밀번호 찾기 요청에 성공한다.")
	void requestFindPassword_success() {
		//given
		String name = member.getName();
		String memberNo = member.getMemberDetail().getMemberNo();

		//when
		SendCodeResponse response = memberFindService.requestFindPassword(name, memberNo);

		//then
		assertThat(response).isNotNull();
		assertThat(response.email()).isEqualTo(member.getMemberDetail().getEmail());

		assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();

		String savedAuthCode = authRedisRepository.getAuthCode(member.getMemberDetail().getEmail());
		assertThat(savedAuthCode).isNotNull();

		String mailContent = GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]);
		assertThat(mailContent).contains(savedAuthCode);
	}

	@Test
	@DisplayName("존재하지 않는 회원번호로 비밀번호 찾기 요청 시도 시 실패한다.")
	void requestFindPassword_fail_when_wrong_member_no() {
		//given
		String wrongMemberNo = "202007070001";

		//when & then
		assertThatExceptionOfType(BusinessException.class)
			.isThrownBy(() -> memberFindService.requestFindPassword(member.getName(), wrongMemberNo))
			.satisfies(exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MemberError.USER_NOT_FOUND));
	}

	@Test
	@DisplayName("회원 정보에 있는 이름과 일치하지 않는 요청일 경우 실패한다.")
	void requestFindPassword_fail_when_miss_match_name() {
		//given
		String wrongName = "다른이름";

		//when & then
		assertThatExceptionOfType(BusinessException.class)
			.isThrownBy(() -> memberFindService.requestFindPassword(wrongName, member.getMemberDetail().getMemberNo()))
			.satisfies(exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MemberError.NAME_MISMATCH));
	}

	@Test
	@DisplayName("올바른 인증 코드로 비밀번호 찾기 검증에 성공하면 임시 토큰이 발급된다.")
	void verifyFindPassword_success() {
		//given
		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();
		String authCode = "123456";

		memberRedisRepository.saveMemberNo(memberEmail, memberNo);
		authRedisRepository.saveAuthCode(memberEmail, authCode);

		//when
		TemporaryTokenResponse response = memberFindService.verifyFindPassword(memberEmail, authCode);

		//then
		assertThat(response).isNotNull();
		assertThat(response.temporaryToken()).isNotNull();

		// 검증 후 레디스에 회원번호가 삭제되었는지 확인
		String savedAuthCode = authRedisRepository.getAuthCode(memberEmail);
		assertThat(savedAuthCode).isNull();
	}

	@Test
	@DisplayName("인증 코드가 일치하지 않으면 비밀번호 찾기 검증 요청에 실패한다.")
	void verifyFindPassword_fail() {
		//given
		String memberEmail = member.getMemberDetail().getEmail();
		String memberNo = member.getMemberDetail().getMemberNo();
		String correctAuthCode = "123456";
		String wrongAuthCode = "111111";

		memberRedisRepository.saveMemberNo(memberEmail, memberNo);
		authRedisRepository.saveAuthCode(memberEmail, correctAuthCode);

		//when & then
		assertThatExceptionOfType(BusinessException.class)
			.isThrownBy(() -> memberFindService.verifyFindPassword(memberEmail, wrongAuthCode))
			.satisfies(exception ->
				assertThat(exception.getErrorCode()).isEqualTo(AuthError.INVALID_AUTH_CODE));
	}
}
