package com.sudo.raillo.support.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import com.sudo.raillo.support.container.TestContainerInitializer;
import com.sudo.raillo.support.extension.RedisCleanupExtension;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = TestContainerInitializer.class)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith({RedisCleanupExtension.class})
public @interface RedisTest {
}
