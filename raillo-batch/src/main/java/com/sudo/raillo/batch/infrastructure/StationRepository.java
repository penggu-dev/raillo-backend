package com.sudo.raillo.batch.infrastructure;

import com.sudo.raillo.train.domain.Station;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {

	List<Station> findByStationNameIn(Collection<String> stationNames);
}
