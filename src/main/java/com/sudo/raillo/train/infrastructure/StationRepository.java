package com.sudo.raillo.train.infrastructure;

import com.sudo.raillo.train.domain.Station;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {

	Optional<Station> findByStationName(String stationName);
}
