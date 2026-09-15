package com.sudo.raillo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.sudo.raillo.support.container.TestContainerInitializer;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestContainerInitializer.class)
class RailloApplicationTests {

	@Test
	void contextLoads() {
	}

}
