package com.sudo.raillo.booking.infrastructure;

import static com.sudo.raillo.booking.domain.QBooking.booking;
import static com.sudo.raillo.booking.domain.QTicket.ticket;
import static com.sudo.raillo.order.domain.QOrder.order;
import static com.sudo.raillo.payment.domain.QPayment.payment;
import static com.sudo.raillo.train.domain.QSeat.seat;
import static com.sudo.raillo.train.domain.QTrain.train;
import static com.sudo.raillo.train.domain.QTrainCar.trainCar;
import static com.sudo.raillo.train.domain.QTrainSchedule.trainSchedule;

import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sudo.raillo.booking.application.dto.projection.QReceiptProjection;
import com.sudo.raillo.booking.application.dto.projection.ReceiptProjection;
import com.sudo.raillo.booking.domain.Ticket;
import com.sudo.raillo.train.domain.QScheduleStop;
import com.sudo.raillo.train.domain.QStation;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TicketQueryRepository {

	private final JPAQueryFactory queryFactory;

	/**
	 * 영수증 조회용 - Projection으로 필요한 데이터만 조회 (1개 쿼리)
	 */
	public Optional<ReceiptProjection> findReceiptByTicket(Ticket ticketEntity) {
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
				ticket.passengerType,
				payment.paymentMethod,
				payment.paidAt,
				payment.paymentKey,
				ticket.fare
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

		return Optional.ofNullable(receiptProjection);
	}
}
