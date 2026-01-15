package com.sudo.raillo.event.admin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sudo.raillo.event.domain.OutboxEvent;
import com.sudo.raillo.event.domain.OutboxStatus;
import com.sudo.raillo.event.handler.OutboxEventHandlerRegistry;
import com.sudo.raillo.event.infrastructure.OutboxEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * Outbox 관리자 API
 */
@RestController
@RequestMapping("/admin/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {

	private final OutboxEventRepository outboxRepository;
	private final OutboxEventHandlerRegistry handlerRegistry;

	/**
	 * 상태별 이벤트 통계
	 */
	@GetMapping("/stats")
	public Map<OutboxStatus, Long> getStats() {
		return outboxRepository.countByStatus();
	}

	/**
	 * 등록된 핸들러 목록
	 */
	@GetMapping("/handlers")
	public Set<String> getRegisteredHandlers() {
		return handlerRegistry.getRegisteredEventTypes();
	}

	/**
	 * 상태별 이벤트 목록 조회
	 */
	@GetMapping("/events")
	public Page<OutboxEventResponse> getEventsByStatus(
		@RequestParam OutboxStatus status,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {

		PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());

		return outboxRepository.findByStatus(status, pageRequest)
			.map(OutboxEventResponse::from);
	}

	/**
	 * DEAD 이벤트 목록 조회 (편의 API)
	 */
	@GetMapping("/dead")
	public Page<OutboxEventResponse> getDeadEvents(
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size) {

		return getEventsByStatus(OutboxStatus.DEAD, page, size);
	}

	/**
	 * 이벤트 상세 조회
	 */
	@GetMapping("/{eventId}")
	public OutboxEventResponse getEvent(@PathVariable Long eventId) {
		OutboxEvent event = outboxRepository.findById(eventId)
			.orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다: " + eventId));

		return OutboxEventResponse.from(event);
	}

	/**
	 * 수동 재시도 (DEAD/FAILED → PENDING)
	 */
	@PostMapping("/{eventId}/retry")
	public OutboxEventResponse manualRetry(@PathVariable Long eventId) {
		OutboxEvent event = outboxRepository.findById(eventId)
			.orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다: " + eventId));

		if (event.getStatus() != OutboxStatus.DEAD && event.getStatus() != OutboxStatus.FAILED) {
			throw new IllegalStateException("DEAD 또는 FAILED 상태의 이벤트만 재시도 가능합니다");
		}

		event.resetForRetry();
		outboxRepository.save(event);

		return OutboxEventResponse.from(event);
	}

	/**
	 * DEAD 이벤트 일괄 재시도
	 */
	@PostMapping("/dead/retry-all")
	public int retryAllDeadEvents() {
		List<OutboxEvent> deadEvents = outboxRepository.findByStatus(OutboxStatus.DEAD);

		deadEvents.forEach(OutboxEvent::resetForRetry);
		outboxRepository.saveAll(deadEvents);

		return deadEvents.size();
	}
}
