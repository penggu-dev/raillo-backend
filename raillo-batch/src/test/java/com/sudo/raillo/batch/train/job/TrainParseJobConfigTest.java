package com.sudo.raillo.batch.train.job;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.sudo.raillo.batch.train.application.facade.StationFareBatchFacade;
import com.sudo.raillo.batch.train.application.facade.TrainScheduleBatchFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

class TrainParseJobConfigTest {

	@DisplayName("파싱 Tasklet은 시간표와 운임표를 순서대로 처리한다")
	@Test
	void parse_tasklet_processes_schedule_and_fare_in_order() throws Exception {
		// given
		TrainScheduleBatchFacade trainScheduleBatchFacade = mock(TrainScheduleBatchFacade.class);
		StationFareBatchFacade stationFareBatchFacade = mock(StationFareBatchFacade.class);
		TrainParseJobConfig jobConfig = new TrainParseJobConfig(
			mock(JobRepository.class),
			mock(PlatformTransactionManager.class),
			trainScheduleBatchFacade,
			stationFareBatchFacade
		);

		// when
		jobConfig.trainParseTasklet().execute(null, null);

		// then
		var ordered = inOrder(trainScheduleBatchFacade, stationFareBatchFacade);
		ordered.verify(trainScheduleBatchFacade).parseTrainSchedule();
		ordered.verify(stationFareBatchFacade).parseStationFare();
	}
}
