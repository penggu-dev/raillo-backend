package com.sudo.raillo.booking.presentation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.application.service.TicketService;
import com.sudo.raillo.booking.docs.TicketControllerDoc;
import com.sudo.raillo.booking.success.TicketSuccess;
import com.sudo.raillo.global.success.SuccessResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/booking/ticket")
@RequiredArgsConstructor
public class TicketController implements TicketControllerDoc {

	private final TicketService ticketService;

	@GetMapping
	public SuccessResponse<List<TicketReadResponse>> getMyTickets(@AuthenticationPrincipal UserDetails userDetails) {
		String username = userDetails.getUsername();
		List<TicketReadResponse> tickets = ticketService.getMyTickets(username);
		return SuccessResponse.of(TicketSuccess.TICKET_LIST_GET_SUCCESS, tickets);
	}
}
