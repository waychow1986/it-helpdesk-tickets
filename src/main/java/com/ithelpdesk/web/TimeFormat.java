package com.ithelpdesk.web;

import com.ithelpdesk.config.AppProperties;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** 以公司时区格式化时间，模板中以 {@code @timeFormat} 使用。 */
@Component("timeFormat")
public class TimeFormat {

    private final DateTimeFormatter formatter;

    public TimeFormat(AppProperties properties) {
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(properties.zoneId());
    }

    public String format(Instant instant) {
        return instant == null ? "" : formatter.format(instant);
    }
}
