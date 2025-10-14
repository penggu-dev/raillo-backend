package com.sudo.raillo.booking.infrastructure.reservation;

import java.util.List;

import com.sudo.raillo.booking.application.dto.ReservationInfo;

public interface ReservationRepositoryCustom {

	List<ReservationInfo> findReservationDetail(Long memberId);

	List<ReservationInfo> findReservationDetail(Long memberId, List<Long> reservationIds);
}
