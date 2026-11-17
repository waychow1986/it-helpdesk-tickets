package com.ithelpdesk.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有"当前时刻"均取自该 Clock（TD-010）。 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class ClockConfig {

    @Bean
    public Clock clock(AppProperties properties) {
        return Clock.system(properties.zoneId());
    }
}
