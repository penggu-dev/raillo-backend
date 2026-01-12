package com.sudo.raillo.global.event.application;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sudo.raillo.global.event.domain.OutboxEvent;
import com.sudo.raillo.global.event.domain.OutboxStatus;
import com.sudo.raillo.global.event.infrastructure.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProcessor {

	private final OutboxEventRepository outboxEventRepository;
	private final OutboxEventHandler outboxEventHandler;

	// 제대로된 배치 로직 필요
	@Scheduled(fixedDelay = 1000)
	@Transactional
	public void processOutboxEvents() {
		List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEventsWithLock(10); // 이벤트 처리를 몇 개씩 처리할지?

		pendingEvents.forEach(this::processEvent);
	}

	private void processEvent(OutboxEvent event) {
		try {
			outboxEventHandler.handle(event.getId());
		} catch (Exception e) {
			// 실패 시 재시도 횟수 증가 및 재시도 횟수 초과 시 상태값 변경
			event.fail();

			if (event.getStatus() == OutboxStatus.FAILED) {
				log.error("[Outbox 이벤트 처리 최종 실패] id={}, aggregateId={}",
					event.getId(), event.getAggregateId());
			}
		}
	}

	// 배치 삭제 필요
	@Scheduled(cron = "0 0 3 * * *")
	public void cleanUpOldEvent() {
		int deleted = outboxEventRepository.deleteCompletedEvents(LocalDateTime.now().minusMinutes(7));

		log.info("[완료된 Outbox 정리] {}개 이벤트 삭제", deleted);
	}
}
