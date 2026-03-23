package com.sudo.raillo.train.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.sudo.raillo.train.domain.StationFare;

/**
 * 구간 별 요금 Repository
 * 출발역-도착역 구간의 요금 정보 조회
 */
@Repository
public interface StationFareRepository extends JpaRepository<StationFare, Long> {

	/**
	 * 출발역-도착역 구간의 요금 정보 조회
	 * - 복합 인덱스(departure_station_id, arrival_station_id)를 활용한 직접 조회
	 * - LEFT JOIN 대신 FK 컬럼 직접 조건으로 불필요한 JOIN 제거
	 */
	@Query("SELECT sf FROM StationFare sf "
		+ "WHERE sf.departureStation.id = :departureStationId "
		+ "AND sf.arrivalStation.id = :arrivalStationId")
	Optional<StationFare> findByDepartureStationIdAndArrivalStationId(
		@Param("departureStationId") Long departureStationId,
		@Param("arrivalStationId") Long arrivalStationId);
}
