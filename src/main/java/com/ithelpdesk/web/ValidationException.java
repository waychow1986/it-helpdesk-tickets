package com.ithelpdesk.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 业务校验失败，携带逐字段错误信息（字段名 → 提示）。 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public ValidationException(Map<String, String> errors) {
        super("validation failed");
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    /** 第一条错误信息，用于 flash 提示。 */
    public String firstMessage() {
        return errors.values().stream().findFirst().orElse("");
    }
}
