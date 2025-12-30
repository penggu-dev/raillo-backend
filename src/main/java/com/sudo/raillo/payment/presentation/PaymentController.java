package com.sudo.raillo.payment.presentation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.payment.application.PaymentFacade;
import com.sudo.raillo.payment.application.PaymentService;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentPrepareRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentPrepareResponse;
import com.sudo.raillo.payment.success.PaymentSuccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Payments", description = "결제 API")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

	private final PaymentService paymentService;
	private final PaymentFacade paymentFacade;

	@Operation(summary = "결제 준비", description = "Redis의 임시 예약(PendingBooking) 목록을 바탕으로 주문(Order)과 결제(Payment)를 생성합니다. "
		+ "토스페이먼츠 결제 위젯 초기화에 필요한 orderId와 amount를 반환합니다.")
	@PostMapping("/prepare")
	public SuccessResponse<PaymentPrepareResponse> preparePayment(
		@RequestBody @Valid PaymentPrepareRequest request,
		@AuthenticationPrincipal UserDetails userDetails) {
		String memberNo = userDetails.getUsername();
		PaymentPrepareResponse response = paymentFacade.preparePayment(request, memberNo);

		return SuccessResponse.of(PaymentSuccess.PAYMENT_PREPARE_SUCCESS, response);
	}

	@Operation(summary = "결제 승인", description = "토스페이먼츠 결제 승인 처리를 수행합니다. 클라이언트가 토스로부터 받은 paymentKey, orderId, amount를 전달받아 결제를 승인합니다.")
	@PostMapping("/confirm")
	public SuccessResponse<PaymentConfirmResponse> confirmPayment(
		@RequestBody @Valid PaymentConfirmRequest request,
		@AuthenticationPrincipal UserDetails userDetails) {
		String memberNo = userDetails.getUsername();
		PaymentConfirmResponse response = paymentFacade.confirmPayment(request, memberNo);

		return SuccessResponse.of(PaymentSuccess.PAYMENT_CONFIRM_SUCCESS, response);
	}
}
