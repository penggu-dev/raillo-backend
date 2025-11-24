package com.sudo.raillo.payment.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.booking.application.dto.response.ReservationCreateResponse;
import com.sudo.raillo.booking.application.facade.ReservationFacade;
import com.sudo.raillo.booking.domain.ProvisionalBooking;
import com.sudo.raillo.payment.application.dto.request.PaymentCallbackRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentRequestRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentRequestResponse;
import com.sudo.raillo.payment.domain.Payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

	private final PaymentService paymentService;
	private final ReservationFacade reservationFacade;
	// private final PaymentGateway paymentGateway;

	@Value("${payment.toss.success-url}")
	private String successUrl;

	@Value("${payment.toss.fail-url}")
	private String failUrl;

	/**
	 * 결제 요청 생성 (결제 페이지 URL 반환)
	 */
	public PaymentRequestResponse createPaymentRequest(PaymentRequestRequest request) {
		// 1. Redis에서 임시 예약 조회
		ProvisionalBooking provisional = reservationFacade.getProvisionalBooking(
			request.bookingId()
		);

		// 2. Payment 엔티티 생성 (PENDING 상태)
		Payment payment = null;

		// 3. 결제 페이지 URL 생성 (토스페이먼츠)
		String paymentUrl = buildTossPaymentUrl(
			payment.getId(),
			request.bookingId(),
			provisional.getTotalFare()
		);

		log.info("결제 요청 생성 - paymentId: {}, bookingId: {}",
			payment.getId(), request.bookingId());

		return PaymentRequestResponse.builder()
			.paymentId(payment.getId())
			.paymentUrl(paymentUrl)
			.amount(provisional.getTotalFare())
			.build();
	}

	/**
	 * 결제 완료 콜백 처리
	 */
	@Transactional
	public void processPaymentCallback(PaymentCallbackRequest request, String memberNo) {
		log.info("결제 콜백 수신 - bookingId: {}, paymentKey: {}",
			request.bookingId(), request.paymentKey());

		// 1. 결제 조회

		// 2. 금액 검증

		// 3. 결제사 최종 승인 요청

		// 4. Booking 모듈에 예약 확정 요청
		ReservationCreateResponse reservation = reservationFacade.confirmBookingByPayment(
			memberNo,
			request.bookingId(),
			1L
		);

		// 5. Ticket 생성

		// 6. Payment 상태 업데이트 (COMPLETED)

		log.info("결제 처리 완료 - paymentId: {}, reservationId: {}",
			1L, reservation.reservationId());
	}

	/**
	 * 토스페이먼츠 결제 페이지 URL 생성
	 */
	private String buildTossPaymentUrl(Long paymentId, String bookingId, Integer amount) {
		return String.format(
			"https://payment.toss.im/payment?clientKey=%s&orderId=%s&amount=%d&successUrl=%s&failUrl=%s",
			// clientKey는 application.yml에서 주입
			bookingId,
			amount,
			successUrl,
			failUrl
		);
	}
}
