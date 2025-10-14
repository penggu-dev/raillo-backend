package com.sudo.raillo.booking.application;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.domain.Qr;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.QrRepository;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QrService {

	private final QrRepository qrRepository;

	public Qr createQr() {
		Qr qr = Qr.builder()
			.isUsable(false)
			.scanCount(0)
			.build();
		try {
			return qrRepository.save(qr);
		} catch (Exception e) {
			throw new BusinessException(BookingError.QR_CREATE_FAILED);
		}
	}
}
