package com.ithelpdesk.support;

import com.ithelpdesk.config.AppProperties;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 以可变测试时钟替换生产 Clock Bean。 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock testClock(AppProperties properties) {
        return new MutableClock(Instant.now(), properties.zoneId());
    }
}
