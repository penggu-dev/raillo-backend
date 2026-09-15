package com.sudo.raillo.booking.application.mapper;

import com.sudo.raillo.booking.application.dto.projection.ReceiptProjection;
import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import org.springframework.stereotype.Component;

@Component
public class TicketMapper {

	public ReceiptResponse convertToReceiptResponse(ReceiptProjection receiptProjection) {
		return new ReceiptResponse(
			receiptProjection.getTicketNumber(),
			receiptProjection.getTicketCreatedAt(),
			receiptProjection.getTrainNumber(),
			receiptProjection.getCarNumber(),
			receiptProjection.getCarType(),
			receiptProjection.getSeatNumber(),
			receiptProjection.getOperationDate(),
			receiptProjection.getDepartureStationName(),
			receiptProjection.getArrivalStationName(),
			receiptProjection.getDepartureTime(),
			receiptProjection.getArrivalTime(),
			receiptProjection.getPassengerType(),
			receiptProjection.getPaymentMethod(),
			receiptProjection.getPaidAt(),
			receiptProjection.getPaymentKey(),
			receiptProjection.getAmount()
		);
	}
}
