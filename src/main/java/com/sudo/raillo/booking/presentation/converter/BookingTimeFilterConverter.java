package com.sudo.raillo.booking.presentation.converter;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import com.sudo.raillo.booking.application.dto.BookingTimeFilter;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.global.exception.error.BusinessException;

@Component
public class BookingTimeFilterConverter implements Converter<String, BookingTimeFilter> {

	@Override
	public BookingTimeFilter convert(String source) {
		return switch (source.toLowerCase()) {
			case "upcoming" -> BookingTimeFilter.UPCOMING;
			case "history" -> BookingTimeFilter.HISTORY;
			case "all" -> BookingTimeFilter.ALL;
			default -> throw new BusinessException(BookingError.INVALID_BOOKING_TIME_FILTER);
		};
	}
}
