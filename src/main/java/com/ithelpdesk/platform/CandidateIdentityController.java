package com.ithelpdesk.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 候选身份端点：返回平台注入的 Delivery ID 与 Candidate SHA，不提供虚构回退值。 */
@RestController
public class CandidateIdentityController {

    private final Environment environment;

    public CandidateIdentityController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping(value = "/api/candidate-identity", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> identity() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("deliveryId", environment.getProperty("AIDELIVERY_DELIVERY_ID", ""));
        body.put("candidateSha", environment.getProperty("AIDELIVERY_CANDIDATE_SHA", ""));
        return body;
    }
}
