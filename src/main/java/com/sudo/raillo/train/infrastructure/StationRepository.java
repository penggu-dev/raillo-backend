package com.sudo.raillo.train.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.train.domain.Station;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {

	List<Station> findByStationNameIn(Collection<String> stationNames);

	Optional<Station> findByStationName(String stationName);

	List<Station> findByStationNameContaining(String keyword);
}
