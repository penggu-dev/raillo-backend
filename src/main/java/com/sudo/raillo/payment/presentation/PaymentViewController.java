package com.sudo.raillo.payment.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping("/test/payments")
public class PaymentViewController {

	@GetMapping
	public String paymentPage(Model model) {
		log.info(">>> PaymentViewController /payments/test HIT");
		model.addAttribute("clientKey", "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm");
		model.addAttribute("orderId", "ORDER_" + System.currentTimeMillis());
		model.addAttribute("amount", 1000);
		return "payment";
	}

	@GetMapping("/success")
	public String success(
		@RequestParam String paymentKey,
		@RequestParam String orderId,
		@RequestParam Long amount,
		Model model
	) {
		model.addAttribute("paymentKey", paymentKey);
		model.addAttribute("orderId", orderId);
		model.addAttribute("amount", amount);
		return "payment-success";
	}
}
