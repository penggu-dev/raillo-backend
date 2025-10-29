package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.domain.Qr;
import com.sudo.raillo.booking.infrastructure.QrRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
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
