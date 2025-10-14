package com.sudo.raillo.booking.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sudo.raillo.booking.domain.Qr;

public interface QrRepository extends JpaRepository<Qr, Long> {
}
