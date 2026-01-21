package com.sudo.raillo.global.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudo.raillo.global.event.domain.Event;
import com.sudo.raillo.global.event.infrastructure.EventRepository;
import com.sudo.raillo.payment.application.dto.event.PaymentCompletedEvent;
import com.sudo.raillo.support.annotation.ServiceTest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@ServiceTest
class EventProcessorTest {

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private EventForwarder eventForwarder;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("10개의 이벤트를 병렬로 처리한다 - EventForwarder 동작 시뮬레이션")
	void process_10EventsInParallel_allProcessed() throws Exception {
		// given - 트랜잭션을 커밋해야 별도 스레드에서 볼 수 있음
		PaymentCompletedEvent paymentEvent = new PaymentCompletedEvent(9999L);
		String payload = objectMapper.writeValueAsString(paymentEvent);

		transactionTemplate.execute(status -> {
			for (int i = 0; i < 5; i++) {
				Event event = Event.create(
					"Payment",
					(long) i,
					"PaymentCompletedEvent",
					payload
				);
				eventRepository.save(event);
			}
			return null;
		});

		// when - EventForwarder가 병렬로 처리
		eventForwarder.forwardEvents();

		// 병렬 스레드가 완료될 때까지 대기
		Thread.sleep(5000);

		// then
		List<Event> events = eventRepository.findAll();
		log.info("[결과] 총 이벤트 수: {}", events.size());

		for (Event event : events) {
			log.info("[이벤트] id={}, status={}, retryCount={}",
				event.getId(), event.getStatus(), event.getRetryCount());
			// BookingEventHandler에서 Order를 찾지 못해 실패 → retryCount 증가
			assertThat(event.getRetryCount()).isEqualTo(1);
		}
	}
}
