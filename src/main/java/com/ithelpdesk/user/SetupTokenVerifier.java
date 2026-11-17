package com.ithelpdesk.user;

import com.ithelpdesk.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 一次性设置口令校验（TD-019）：启动时读取 app.setup.token，去首尾空白后长度不少于 16 视为已配置，
 * 只保存 SHA-256 摘要（不保留原值）；提交值做 SHA-256 后以常量时间比较。不输出口令的值或长度。
 */
@Component
public class SetupTokenVerifier {

    /** 口令最小长度（去首尾空白后）。 */
    public static final int MIN_LENGTH = 16;

    private static final Logger LOG = LoggerFactory.getLogger(SetupTokenVerifier.class);

    /** 校验结果。 */
    public enum Result {
        NOT_CONFIGURED, EMPTY, MISMATCH, OK
    }

    private final byte[] digest;

    @Autowired
    public SetupTokenVerifier(AppProperties properties) {
        this(properties.setup().token());
    }

    SetupTokenVerifier(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.length() >= MIN_LENGTH) {
            this.digest = sha256(value);
        } else {
            this.digest = null;
            LOG.warn("未提供有效的设置口令，设置初始管理员流程无法完成");
        }
    }

    public boolean isConfigured() {
        return digest != null;
    }

    public Result verify(String submitted) {
        if (digest == null) {
            return Result.NOT_CONFIGURED;
        }
        String value = submitted == null ? "" : submitted.trim();
        if (value.isEmpty()) {
            return Result.EMPTY;
        }
        return MessageDigest.isEqual(digest, sha256(value)) ? Result.OK : Result.MISMATCH;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @Override
    public String toString() {
        return "SetupTokenVerifier[configured=" + isConfigured() + "]";
    }
}
