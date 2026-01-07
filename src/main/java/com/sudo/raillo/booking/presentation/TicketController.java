package com.sudo.raillo.booking.presentation;

import com.sudo.raillo.booking.application.dto.request.ReceiptRequest;
import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.docs.TicketControllerDoc;
import com.sudo.raillo.booking.success.TicketSuccess;
import com.sudo.raillo.global.success.SuccessResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/booking/ticket")
@RequiredArgsConstructor
public class TicketController implements TicketControllerDoc {

	private final TicketService ticketService;

	@GetMapping
	public SuccessResponse<List<TicketReadResponse>> getMyTickets(
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();
		List<TicketReadResponse> tickets = ticketService.getMyTickets(memberNo);
		return SuccessResponse.of(TicketSuccess.TICKET_LIST_SUCCESS, tickets);
	}

	@GetMapping("/receipt")
	public SuccessResponse<ReceiptResponse> getReceipt(
		ReceiptRequest request,
		@AuthenticationPrincipal UserDetails userDetails
	) {
		String memberNo = userDetails.getUsername();
		ReceiptResponse response = ticketService.getReceipt(memberNo, request.ticketId());
		return SuccessResponse.of(TicketSuccess.RECEIPT_SUCCESS, response);
	}
}
