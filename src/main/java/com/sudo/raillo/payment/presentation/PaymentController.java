package com.sudo.raillo.payment.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.global.success.SuccessResponse;
import com.sudo.raillo.payment.application.PaymentFacade;
import com.sudo.raillo.payment.application.PaymentService;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessAccountRequest;
import com.sudo.raillo.payment.application.dto.request.PaymentProcessCardRequest;
import com.sudo.raillo.payment.application.dto.response.PaymentCancelResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentHistoryResponse;
import com.sudo.raillo.payment.application.dto.response.PaymentProcessResponse;
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

	@Operation(
		summary = "결제 승인",
		description = "토스페이먼츠 결제 승인 처리를 수행합니다. 클라이언트가 토스로부터 받은 paymentKey, orderId, amount를 전달받아 결제를 승인합니다."
	)
	@PostMapping("/confirm")
	public SuccessResponse<PaymentConfirmResponse> confirmPayment(@RequestBody @Valid PaymentConfirmRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();
		PaymentConfirmResponse response = paymentFacade.confirmPayment(request, memberNo);

		return SuccessResponse.of(PaymentSuccess.PAYMENT_CONFIRM_SUCCESS, response);
	}

	@Operation(summary = "결제 취소", description = "완료된 결제를 취소 및 환불처리 합니다.")
	@PostMapping("/{paymentKey}/cancel")
	public SuccessResponse<PaymentCancelResponse> cancelPayment(@PathVariable String paymentKey,
		@AuthenticationPrincipal UserDetails userDetails) {
		String memberNo = userDetails.getUsername();
		PaymentCancelResponse response = paymentService.cancelPayment(memberNo, paymentKey);

		return SuccessResponse.of(PaymentSuccess.PAYMENT_CANCEL_SUCCESS, response);
	}

	@Operation(summary = "결제 내역 조회", description = "사용자의 결제 내역을 조회합니다.")
	@GetMapping
	public SuccessResponse<List<PaymentHistoryResponse>> getPaymentHistory(
		@AuthenticationPrincipal UserDetails userDetails) {

		String memberNo = userDetails.getUsername();
		List<PaymentHistoryResponse> response = paymentService.getPaymentHistory(memberNo);

		return SuccessResponse.of(PaymentSuccess.PAYMENT_HISTORY_SUCCESS, response);
	}
}
