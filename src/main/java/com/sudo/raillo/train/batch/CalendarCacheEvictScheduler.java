package com.sudo.raillo.train.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 운행 캘린더 캐시 삭제 스케줄러
 */
@Slf4j
@Component
@EnableScheduling
public class CalendarCacheEvictScheduler {

	/**
	 * 매일 자정(00:00:00)에 운행 캘린더 캐시 삭제
	 */
	@Scheduled(cron = "0 0 0 * * *")
	@CacheEvict(value = "train:calendar", allEntries = true)
	public void evictCalendarCache() {
		log.info("자정 스케줄러 동작: 운행 캘린더 캐시 초기화");
	}
}
