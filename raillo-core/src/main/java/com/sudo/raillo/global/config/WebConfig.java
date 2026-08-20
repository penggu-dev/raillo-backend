package com.sudo.raillo.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.sudo.raillo.booking.presentation.converter.BookingTimeFilterConverter;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

	private final BookingTimeFilterConverter bookingTimeFilterConverter;

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(bookingTimeFilterConverter);
	}
}
