package com.sudo.raillo.booking.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.*;
import static com.sudo.raillo.booking.domain.QTicket.*;
import static com.sudo.raillo.order.domain.QOrder.*;
import static com.sudo.raillo.payment.domain.QPayment.*;
import static com.sudo.raillo.train.domain.QSeat.*;
import static com.sudo.raillo.train.domain.QTrain.*;
import static com.sudo.raillo.train.domain.QTrainCar.*;
import static com.sudo.raillo.train.domain.QTrainSchedule.*;

import com.sudo.raillo.booking.application.dto.projection.ReceiptProjection;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.application.dto.projection.QReceiptProjection;
import com.sudo.raillo.booking.application.dto.response.ReceiptResponse;
import com.sudo.raillo.booking.application.dto.response.TicketReadResponse;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.booking.domain.status.TicketStatus;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QStation;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TicketQueryRepository {

	private final JPAQueryFactory queryFactory;

	public List<TicketReadResponse> findPaidTicketResponsesByMemberId(Long memberId) {
		QScheduleStop departureStop = new QScheduleStop("departureStop");
		QScheduleStop arrivalStop = new QScheduleStop("arrivalStop");
		QStation departureStation = new QStation("departureStation");
		QStation arrivalStation = new QStation("arrivalStation");

		return queryFactory
			.select(Projections.constructor(
				TicketReadResponse.class,
				ticket.id,
				booking.id,
				trainSchedule.operationDate,
				departureStation.id,
				departureStation.stationName,
				departureStop.departureTime,
				arrivalStation.id,
				arrivalStation.stationName,
				arrivalStop.arrivalTime,
				Expressions.stringTemplate("LPAD(CAST({0} AS string), 3, '0')", train.trainNumber),
				train.trainName,
				trainCar.carType,
				trainCar.carNumber,
				seat.seatRow,
				seat.seatColumn,
				seat.seatType
			))
			.from(ticket)
			.join(ticket.seat, seat)
			.join(ticket.booking, booking)
			.join(booking.trainSchedule, trainSchedule)
			.join(trainSchedule.train, train)
			.join(booking.departureStop, departureStop)
			.join(booking.arrivalStop, arrivalStop)
			.join(seat.trainCar, trainCar)
			.join(departureStop.station, departureStation)
			.join(arrivalStop.station, arrivalStation)
			.where(
				booking.member.id.eq(memberId)
					.and(ticket.ticketStatus.eq(TicketStatus.ISSUED))
					.and(arrivalStop.station.id.eq(arrivalStation.id))
					.and(departureStop.station.id.eq(departureStation.id))
					.and(departureStop.stopOrder.lt(arrivalStop.stopOrder))
			)
			.orderBy(trainSchedule.operationDate.desc(), trainSchedule.departureTime.desc())
			.fetch();
	}

	/**
	 * 영수증 조회용 - Projection으로 필요한 데이터만 조회 (1개 쿼리)
	 */
	public ReceiptResponse findReceiptByTicket(Ticket ticketEntity) {
		QScheduleStop departureStop = new QScheduleStop("departureStop");
		QScheduleStop arrivalStop = new QScheduleStop("arrivalStop");
		QStation departureStation = new QStation("departureStation");
		QStation arrivalStation = new QStation("arrivalStation");

		ReceiptProjection receiptProjection = queryFactory
			.select(new QReceiptProjection(
				ticket.ticketNumber,
				ticket.createdAt,
				Expressions.stringTemplate("LPAD(CAST({0} AS string), 3, '0')", train.trainNumber),
				trainCar.carNumber,
				trainCar.carType,
				Expressions.stringTemplate("CONCAT({0}, {1})", seat.seatRow, seat.seatColumn),
				trainSchedule.operationDate,
				departureStation.stationName,
				arrivalStation.stationName,
				departureStop.departureTime,
				arrivalStop.arrivalTime,
				payment.paymentMethod,
				payment.paidAt,
				payment.paymentKey,
				payment.amount
			))
			.from(ticket)
			.join(ticket.booking, booking)
			.join(ticket.seat, seat)
			.join(seat.trainCar, trainCar)
			.join(trainCar.train, train)
			.join(booking.order, order)
			.join(payment).on(payment.order.eq(order))
			.join(booking.trainSchedule, trainSchedule)
			.join(booking.departureStop, departureStop)
			.join(booking.arrivalStop, arrivalStop)
			.join(departureStop.station, departureStation)
			.join(arrivalStop.station, arrivalStation)
			.where(ticket.id.eq(ticketEntity.getId()))
			.fetchOne();

		return ReceiptResponse.from(receiptProjection);
	}
}
