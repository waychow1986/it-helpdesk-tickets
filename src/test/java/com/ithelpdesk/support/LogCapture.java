package com.ithelpdesk.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** 捕获指定类的日志事件（用于断言日志不含敏感内容）。 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCapture(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return appender.list;
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public boolean has(Level level) {
        return appender.list.stream().anyMatch(e -> e.getLevel() == level);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
