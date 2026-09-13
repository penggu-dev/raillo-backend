package com.sudo.raillo.batch.infrastructure;

import com.sudo.raillo.train.domain.Train;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainRepository extends JpaRepository<Train, Long> {

	List<Train> findByTrainNumberIn(Collection<Integer> trainNumbers);
}
