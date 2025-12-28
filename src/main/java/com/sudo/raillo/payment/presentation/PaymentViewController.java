package com.sudo.raillo.payment.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.sudo.raillo.payment.application.dto.TossPaymentConfirmResponse;
import com.sudo.raillo.payment.application.dto.request.PaymentConfirmRequest;
import com.sudo.raillo.payment.infrastructure.TossPaymentClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("/test/payments")
public class PaymentViewController {

	private final TossPaymentClient tossPaymentClient;

	@GetMapping
	public String paymentPage(Model model) {
		log.info(">>> PaymentViewController /payments/test HIT");
		model.addAttribute("clientKey", "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm");
		model.addAttribute("orderId", "ORDER_" + System.currentTimeMillis());
		model.addAttribute("amount", 1000);
		return "payment";
	}

	@GetMapping("/confirm")
	public String confirm(
		@RequestParam String paymentKey,
		@RequestParam String orderId,
		@RequestParam Long amount,
		Model model
	) {
		model.addAttribute("paymentKey", paymentKey);
		model.addAttribute("orderId", orderId);
		model.addAttribute("amount", amount);
		return "payment-confirm";
	}

	@GetMapping("/fail")
	public String fail(
		@RequestParam String code,
		@RequestParam String message,
		@RequestParam(required = false) String orderId,
		Model model
	) {
		model.addAttribute("errorCode", code);
		model.addAttribute("errorMessage", message);
		model.addAttribute("orderId", orderId);
		return "payment-fail";
	}

	@PostMapping("/confirm-toss")
	@ResponseBody
	public TossPaymentConfirmResponse confirmTossOnly(
		@RequestBody PaymentConfirmRequest request) {
		return tossPaymentClient.confirmPayment(request);
	}
}
