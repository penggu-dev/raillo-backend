package com.sudo.raillo.booking.redis;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReservationRedisRepository extends CrudRepository<HoldingReservation, String> {
}
