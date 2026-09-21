package com.sudo.raillo.booking.application.service;

import com.sudo.raillo.booking.application.dto.SeatOccupancyQuery;
import com.sudo.raillo.booking.infrastructure.SeatOccupancyQueryRepository;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SeatOccupancyQueryService {
	private final SeatOccupancyQueryRepository repository;

	public Map<Long, Set<Long>> findOccupiedSeatIds(SeatOccupancyQuery query) {
		return repository.findOccupiedSeatIds(query);
	}
}
