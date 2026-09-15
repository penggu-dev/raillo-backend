package com.sudo.raillo.support.repository;

import com.sudo.raillo.train.domain.Train;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 테스트에서만 사용하는 Train 생성 Repository
 */
public interface TrainRepository extends JpaRepository<Train, Long> {
}
