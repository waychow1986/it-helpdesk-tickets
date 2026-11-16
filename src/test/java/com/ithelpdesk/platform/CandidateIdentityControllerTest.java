package com.ithelpdesk.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CandidateIdentityControllerTest {

    @Test
    void returnsInjectedIdentity() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("AIDELIVERY_DELIVERY_ID", "DLV-1")
            .withProperty("AIDELIVERY_CANDIDATE_SHA", "abc123");
        Map<String, String> body = new CandidateIdentityController(env).identity();
        assertThat(body).containsExactly(Map.entry("deliveryId", "DLV-1"), Map.entry("candidateSha", "abc123"));
    }

    @Test
    void missingVariablesYieldEmptyStringsNotFabricatedValues() {
        Map<String, String> body = new CandidateIdentityController(new MockEnvironment()).identity();
        assertThat(body).containsEntry("deliveryId", "").containsEntry("candidateSha", "");
    }
}
