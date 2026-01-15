package com.sudo.raillo.global.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventConfig {

	@Bean(destroyMethod = "shutdown")
	public ExecutorService eventExecutor() {
		return new ThreadPoolExecutor(
			10,
			10,
			60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(200),
			new ThreadPoolExecutor.CallerRunsPolicy()
		);
	}
}
