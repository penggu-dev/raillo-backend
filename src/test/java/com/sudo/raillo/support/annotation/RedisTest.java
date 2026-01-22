package com.sudo.raillo.support.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.sudo.raillo.support.extension.RedisCleanupExtension;
import com.sudo.raillo.support.extension.RedisServerExtension;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith({RedisServerExtension.class, RedisCleanupExtension.class})
public @interface RedisTest {
}
