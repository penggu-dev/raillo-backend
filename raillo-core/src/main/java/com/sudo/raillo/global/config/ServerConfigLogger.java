package com.sudo.raillo.global.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile({"dev","prod"})
@RequiredArgsConstructor
public class ServerConfigLogger {

	private final HikariDataSource dataSource;

	@Value("${server.tomcat.threads.max}")
	private int maxThreads;

	@EventListener(ApplicationReadyEvent.class)
	public void logServerConfig() {
		log.info("Tomcat max threads: {}, HikariCP max pool size: {}", maxThreads, dataSource.getMaximumPoolSize());
	}
}
