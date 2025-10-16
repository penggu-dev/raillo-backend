package com.sudo.raillo.booking.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.request.ReservationDeleteRequest;
import com.sudo.raillo.booking.domain.Reservation;
import com.sudo.raillo.booking.domain.status.ReservationStatus;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.reservation.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationDeletionService {

	private final ReservationRepository reservationRepository;

	/**
	 * 특정 예약을 삭제하는 메서드 - DTO 사용
	 * @param request 예약 삭제 요청 DTO
	 */
	@Transactional
	public void deleteReservation(ReservationDeleteRequest request) {
		try {
			deleteReservation(request.reservationId());
		} catch (Exception e) {
			throw new BusinessException(BookingError.RESERVATION_DELETE_FAILED);
		}
	}

	/**
	 * 특정 예약을 삭제하는 메서드 - 단수 예약 ID 사용
	 * @param reservationId 삭제할 예약의 ID
	 */
	public void deleteReservation(Long reservationId) {
		reservationRepository.deleteById(reservationId);
	}

	/**
	 * 다수의 예약을 삭제하는 메서드 - 복수 예약 ID 사용
	 * @param reservationIds 삭제할 예약의 ID를 원소로 하는 리스트
	 */
	public void deleteReservation(List<Long> reservationIds) {
		reservationRepository.deleteAllByIdInBatch(reservationIds);
	}

	/**
	 * 만료된 예약을 일괄삭제하는 메서드
	 */
	@Transactional
	public void expireReservations() {
		LocalDateTime now = LocalDateTime.now();
		int pageNumber = 0;
		final int pageSize = 500;
		Page<Reservation> expiredPage;
		do {
			Pageable pageable = PageRequest.of(pageNumber, pageSize);
			expiredPage = reservationRepository
				.findAllByExpiresAtBeforeAndReservationStatus(now, ReservationStatus.RESERVED, pageable);
			if (expiredPage.hasContent()) {
				List<Long> expiredList = expiredPage.getContent()
					.stream()
					.map(Reservation::getId)
					.toList();
				reservationRepository.deleteAllByIdInBatch(expiredList);
			}
			pageNumber++;
		} while (expiredPage.hasNext());
	}

	public void deleteAllByMemberId(Long memberId) {
		reservationRepository.deleteAllByMemberId(memberId);
	}
}
