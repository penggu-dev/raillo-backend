package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.ReservationInfo;
import com.sudo.raillo.booking.application.dto.response.ReservationDetail;
import com.sudo.raillo.booking.application.mapper.ReservationMapper;
import com.sudo.raillo.booking.exception.BookingError;
import com.sudo.raillo.booking.infrastructure.ReservationQueryRepository;
import com.sudo.raillo.booking.infrastructure.ReservationRepository;
import com.sudo.raillo.global.exception.error.BusinessException;
import com.sudo.raillo.member.domain.Member;
import com.sudo.raillo.member.exception.MemberError;
import com.sudo.raillo.member.infrastructure.MemberRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService {

	private final MemberRepository memberRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationQueryRepository reservationQueryRepository;
	private final ReservationMapper reservationMapper;

	/**
	 * 예약을 조회하는 메서드
	 * @param memberNo 회원 번호
	 * @param reservationId 예약 ID
	 * @return 예약
	 */
	public ReservationDetail getReservation(String memberNo, Long reservationId) {
		Member member = memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));

		List<ReservationInfo> reservationInfos = reservationQueryRepository.findReservationDetail(
			member.getId(), List.of(reservationId));

		if (reservationInfos.isEmpty()) {
			throw new BusinessException(BookingError.RESERVATION_NOT_FOUND);
		}

		ReservationInfo reservationInfo = reservationInfos.get(0);

		// 만료된 예약이면 삭제 처리
		LocalDateTime now = LocalDateTime.now();
		if (isExpired(reservationInfo, now)) {
			deleteReservation(reservationId);
			throw new BusinessException(BookingError.RESERVATION_EXPIRED);
		}

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

		// 만료된 예약이면 삭제 처리
		LocalDateTime now = LocalDateTime.now();
		List<Long> expiredReservationIds = new ArrayList<>();
		List<ReservationInfo> validReservations = reservationInfos.stream()
			.filter(info -> {
				if (isExpired(info, now)) {
					expiredReservationIds.add(info.reservationId());
					return false;
				}
				return true;
			})
			.toList();

		if (!expiredReservationIds.isEmpty()) {
			deleteReservation(expiredReservationIds);
		}

		return reservationMapper.convertToReservationDetail(validReservations);
	}

	private Member getMember(String memberNo) {
		return memberRepository.findByMemberNo(memberNo)
			.orElseThrow(() -> new BusinessException(MemberError.USER_NOT_FOUND));
	}

	/**
	 * 예약 정보와 주어진 시간을 기준으로 예약이 만료되었는지 판단하는 메서드
	 * @param reservationInfo 예약 정보
	 * @param now 판단 기준이 될 시간
	 * @return 만료 여부
	 */
	private boolean isExpired(ReservationInfo reservationInfo, LocalDateTime now) {
		return reservationInfo.expiresAt().isBefore(now);
	}

	public void deleteReservation(Long reservationId) {
		reservationRepository.deleteById(reservationId);
	}

	public void deleteReservation(List<Long> reservationIds) {
		reservationRepository.deleteAllByIdInBatch(reservationIds);
	}
}
