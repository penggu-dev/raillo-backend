package com.sudo.raillo.booking.redis;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatReservationRedisRepository extends CrudRepository<HoldingSeatReservation, Long> {
}
