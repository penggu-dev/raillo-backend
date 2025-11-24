package com.sudo.raillo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sudo.raillo.support.extension.RedisServerExtension;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(RedisServerExtension.class)
class RailloApplicationTests {

	@Test
	void contextLoads() {
	}

}
