package com.sudo.raillo.global.event.application;

import com.sudo.raillo.global.event.domain.DomainEvent;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EventTypeMapper {

	private final Map<String, Class<?>> eventTypeMap = new HashMap<>();

	@PostConstruct
	void init() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(DomainEvent.class));
		Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.sudo.raillo");

		candidates.forEach(candidate -> {
			try {
				Class<?> eventClass = Class.forName(candidate.getBeanClassName());
				String eventType = eventClass.getSimpleName();
				eventTypeMap.put(eventType, eventClass);
			} catch (ClassNotFoundException e) {
				log.error("[이벤트 클래스 로드 실패] className={}", candidate.getBeanClassName(), e);
			}
		});
	}

	public Class<?> getEventClass(String eventType) {
		return eventTypeMap.get(eventType);
	}
}
