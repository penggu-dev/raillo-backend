package com.sudo.raillo.global.event.application;

import com.sudo.raillo.global.event.domain.Event;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventWakeUpListener {

	private final EventProcessor eventProcessor;
	private final ExecutorService eventExecutor;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onEventPublished(Event event) {
		eventExecutor.execute(() -> eventProcessor.process(event.getId()));
	}
}
