package com.sudo.raillo.booking.infrastructure.ticket;

import java.util.List;

import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;

public interface TicketRepositoryCustom {
	List<TicketReadResponse> findPaidTicketResponsesByMemberId(Long memberId);
}
