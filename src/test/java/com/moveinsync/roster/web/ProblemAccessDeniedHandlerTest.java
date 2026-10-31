package com.moveinsync.roster.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class ProblemAccessDeniedHandlerTest {

    @Test
    void writesA403ProblemBodyWithoutForwarding() throws Exception {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemAccessDeniedHandler(mapper).handle(request, response, new AccessDeniedException("secret detail"));

        assertEquals(403, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        assertNull(response.getForwardedUrl());
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(403, body.get("status").asInt());
        assertEquals("ACCESS_DENIED", body.get("code").asText());
        assertEquals(ApiErrorCode.ACCESS_DENIED.detail(), body.get("detail").asText());
    }
}
