package com.sudo.raillo.booking.application.service;

import org.springframework.stereotype.Service;

import com.sudo.raillo.booking.domain.Qr;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.QrRepository;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class QrService {

	private final QrRepository qrRepository;

	public Qr createQr() {
		Qr qr = Qr.builder()
			.isUsable(false)
			.scanCount(0)
			.build();
		return qrRepository.save(qr);
	}
}
