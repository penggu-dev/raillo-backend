package com.sudo.raillo.batch.infrastructure;

import com.sudo.raillo.train.domain.StationFare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 구간 별 요금 Repository
 * 출발역-도착역 구간의 요금 정보 조회
 */
@Repository
public interface StationFareRepository extends JpaRepository<StationFare, Long> {
}
