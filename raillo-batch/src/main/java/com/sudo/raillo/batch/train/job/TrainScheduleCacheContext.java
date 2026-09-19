package com.sudo.raillo.batch.train.job;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import com.sudo.raillo.train.cache.TrainCacheKey;

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
		put(chunkContext, first(dates), last(dates));
	}

	/**
	 * 시작일을 오늘로 당긴다. 이전 실행이 캐시 적재에서 실패했어도 다음 실행이 메운다.
	 */
	public static void putDatesFromToday(ChunkContext chunkContext, List<LocalDate> dates) {
		if (dates.isEmpty()) {
			return;
		}
		LocalDate today = LocalDate.now(TrainCacheKey.ZONE);
		LocalDate first = first(dates);
		put(chunkContext, first.isAfter(today) ? today : first, last(dates));
	}

	private static void put(ChunkContext chunkContext, LocalDate startDate, LocalDate endDate) {
		ExecutionContext executionContext = chunkContext.getStepContext()
			.getStepExecution()
			.getExecutionContext();

		executionContext.putString(START_DATE, startDate.toString());
		executionContext.putString(END_DATE, endDate.toString());
	}

	private static LocalDate first(List<LocalDate> dates) {
		return dates.stream().min(Comparator.naturalOrder()).orElseThrow();
	}

	private static LocalDate last(List<LocalDate> dates) {
		return dates.stream().max(Comparator.naturalOrder()).orElseThrow();
	}

	public static ExecutionContextPromotionListener promotionListener() {
		ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
		listener.setKeys(new String[] {START_DATE, END_DATE});
		return listener;
	}
}
