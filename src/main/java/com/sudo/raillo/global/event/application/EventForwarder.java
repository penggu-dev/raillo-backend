package com.sudo.raillo.global.event.application;

import com.sudo.raillo.global.event.domain.Event;
import com.sudo.raillo.global.event.infrastructure.EventRepository;
import java.util.List;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventForwarder {

	private static final int BATCH_SIZE = 10;

	private final ExecutorService eventExecutor;
	private final EventRepository eventRepository;
	private final EventProcessor eventProcessor;

	@Transactional
	@Scheduled(initialDelay = 1000L, fixedDelay = 1000L)
	public void forwardEvents() {
		List<Event> pendingEvents = eventRepository.findPendingEvents(BATCH_SIZE);

		List<Long> eventIds = pendingEvents.stream()
			.map(Event::getId)
			.toList();

		eventRepository.updateStatusToRetryAndIncrementCount(eventIds);

		eventIds.forEach(eventId ->
			eventExecutor.execute(() -> eventProcessor.process(eventId)));
	}

	// TODO 완료된 이벤트 제거 메서드
}
