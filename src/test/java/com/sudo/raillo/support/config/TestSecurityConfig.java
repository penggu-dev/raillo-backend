package com.sudo.raillo.support.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class TestSecurityConfig {

	@Bean
	@Primary
	@Profile("test")
	public PasswordEncoder testPasswordEncoder() {
		return new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return rawPassword + "test";
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return (rawPassword + "test").equals(encodedPassword);
			}
		};
	}
}
