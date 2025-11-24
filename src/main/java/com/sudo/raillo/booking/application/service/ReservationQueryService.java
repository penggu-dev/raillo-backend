package com.sudo.raillo.booking.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationQueryRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationQueryService {

	private final MemberRepository memberRepository;
	private final ReservationQueryRepository reservationQueryRepository;
	private final ReservationMapper reservationMapper;

	/**
	 * 예약을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param reservationId 예약 ID
	 * @return 예약
	 */
	@Transactional(readOnly = true)
	public ReservationDetail getReservation(String memberNo, Long reservationId) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		List<ReservationInfo> reservationInfos = reservationQueryRepository.findReservationDetail(
			member.getId(), List.of(reservationId));

		if (reservationInfos.isEmpty()) {
			throw new BusinessException(BookingError.RESERVATION_NOT_FOUND);
		}

		ReservationInfo reservationInfo = reservationInfos.get(0);

		return reservationMapper.convertToReservationDetail(reservationInfo);
	}

	/**
	 * 예약 목록을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @return 예약 목록
	 */
	@Transactional(readOnly = true)
	public List<ReservationDetail> getReservations(String memberNo) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		// 예약 조회
		List<ReservationInfo> reservationInfos = reservationQueryRepository.findReservationDetail(member.getId());

		return reservationMapper.convertToReservationDetail(reservationInfos);
	}
}
