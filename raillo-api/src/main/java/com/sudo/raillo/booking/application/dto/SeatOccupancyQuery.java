package com.sudo.raillo.booking.application.dto;

import java.util.List;

public record SeatOccupancyQuery(long scheduleId, List<Long> carIds, int departureStopOrder, int arrivalStopOrder) {
	public SeatOccupancyQuery {
		carIds = List.copyOf(carIds);
		if (departureStopOrder >= arrivalStopOrder) throw new IllegalArgumentException("출발 구간은 도착보다 앞서야 합니다");
	}
}
