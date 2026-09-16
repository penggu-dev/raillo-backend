package com.sudo.raillo.batch.train.job;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * 스케줄 생성 Step이 정한 대상 날짜를 후속 캐시 적재 Step에 넘긴다.
 *
 * <p>날짜는 ISO 문자열로 넣는다. 실행 컨텍스트는 DB에 직렬화되어 저장되므로 타입에 기대지 않는다.</p>
 */
public final class TrainScheduleCacheContext {

	static final String START_DATE = "trainCacheStartDate";
	static final String END_DATE = "trainCacheEndDate";

	private TrainScheduleCacheContext() {
	}

	public static void putDates(ChunkContext chunkContext, List<LocalDate> dates) {
		if (dates.isEmpty()) {
			return;
		}

		ExecutionContext executionContext = chunkContext.getStepContext()
			.getStepExecution()
			.getExecutionContext();

		executionContext.putString(START_DATE, dates.stream().min(Comparator.naturalOrder()).orElseThrow().toString());
		executionContext.putString(END_DATE, dates.stream().max(Comparator.naturalOrder()).orElseThrow().toString());
	}

	public static ExecutionContextPromotionListener promotionListener() {
		ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
		listener.setKeys(new String[] {START_DATE, END_DATE});
		return listener;
	}
}
