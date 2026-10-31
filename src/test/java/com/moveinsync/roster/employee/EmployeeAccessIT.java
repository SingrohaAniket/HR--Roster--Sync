package com.moveinsync.roster.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.support.AbstractMySqlIT;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class EmployeeAccessIT extends AbstractMySqlIT {

    private static final String ME = "/api/v1/employees/me";
    private static final String BY_ID = "/api/v1/employees/";

    private MockHttpServletResponse call(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void employeeCallingAnotherEmployeeIs403() throws Exception {
        MockHttpServletResponse response = call(BY_ID + "EMP-1001", tokenFor("ACME", "acme.employee"));

        assertEquals(403, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        assertEquals("ACCESS_DENIED", body(response).get("code").asText());
    }

    @Test
    void employeeCallingTheirOwnIdThroughTheAdminRouteIsStill403() throws Exception {
        assertEquals(403, call(BY_ID + "EMP-1002", tokenFor("ACME", "acme.employee")).getStatus());
    }

    @Test
    void employeeGets403ForAMissingIdSoExistenceIsNotRevealed() throws Exception {
        assertEquals(403, call(BY_ID + "EMP-9999", tokenFor("ACME", "acme.employee")).getStatus());
    }

    @Test
    void employeeCallingMeGetsTheirOwnRecordOnly() throws Exception {
        MockHttpServletResponse response = call(ME, tokenFor("ACME", "acme.employee"));

        assertEquals(200, response.getStatus());
        JsonNode me = body(response);
        assertEquals("EMP-1002", me.get("externalId").asText());
        assertEquals("emp1002@acme.example", me.get("email").asText());
        assertEquals("+919000001002", me.get("phone").asText());
        assertEquals("BLR-WHF", me.get("officeCode").asText());
        assertEquals(12.98, me.get("pickupLat").asDouble());
    }

    @Test
    void globexEmployeeGetsTheirOwnTenantsRecord() throws Exception {
        assertEquals("GLX-2001", body(call(ME, tokenFor("GLOBEX", "globex.employee"))).get("externalId").asText());
    }

    @ParameterizedTest
    @CsvSource({"acme.admin", "acme.operator"})
    void nonEmployeeCallingMeIs403(String username) throws Exception {
        MockHttpServletResponse response = call(ME, tokenFor("ACME", username));

        assertEquals(403, response.getStatus());
        assertEquals("ACCESS_DENIED", body(response).get("code").asText());
    }

    @ParameterizedTest
    @CsvSource({"acme.admin", "acme.operator"})
    void adminAndOperatorCanReadTheirOwnTenant(String username) throws Exception {
        MockHttpServletResponse response = call(BY_ID + "EMP-1001", tokenFor("ACME", username));

        assertEquals(200, response.getStatus());
        assertEquals("EMP-1001", body(response).get("externalId").asText());
    }

    @ParameterizedTest
    @CsvSource({"acme.admin", "acme.operator"})
    void acmeTokenRequestingGlobexEmployeeIs404Not403(String username) throws Exception {
        MockHttpServletResponse response = call(BY_ID + "GLX-2001", tokenFor("ACME", username));

        assertEquals(404, response.getStatus());
        assertEquals("NOT_FOUND", body(response).get("code").asText());
    }

    @Test
    void globexCanReadThatSameEmployee() throws Exception {
        assertEquals(200, call(BY_ID + "GLX-2001", tokenFor("GLOBEX", "globex.admin")).getStatus());
    }

    @Test
    void globexTokenRequestingAcmeEmployeeIs404() throws Exception {
        assertEquals(404, call(BY_ID + "EMP-1001", tokenFor("GLOBEX", "globex.admin")).getStatus());
    }

    @Test
    void anotherTenantsEmployeeLooksExactlyLikeOneThatDoesNotExist() throws Exception {
        String acmeAdmin = tokenFor("ACME", "acme.admin");

        JsonNode otherTenant = body(call(BY_ID + "GLX-2001", acmeAdmin));
        JsonNode nonexistent = body(call(BY_ID + "GLX-9999", acmeAdmin));

        assertEquals(fieldNames(otherTenant), fieldNames(nonexistent));
        for (String field : List.of("type", "title", "status", "detail", "code")) {
            assertEquals(nonexistent.get(field), otherTenant.get(field), field);
        }
    }

    @Test
    void aClientSuppliedTenantIdIsIgnored() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(BY_ID + "GLX-2001")
                        .header("Authorization", "Bearer " + tokenFor("ACME", "acme.admin"))
                        .header("X-Tenant-Id", "2")
                        .header("tenantId", "2")
                        .param("tenantId", "2"))
                .andReturn().getResponse();

        assertEquals(404, response.getStatus());
    }

    @Test
    void externalIdsAreCaseSensitive() throws Exception {
        assertEquals(404, call(BY_ID + "emp-1001", tokenFor("ACME", "acme.admin")).getStatus());
    }

    @Test
    void theTenantDoesNotOutliveTheRequest() throws Exception {
        call(BY_ID + "EMP-1001", tokenFor("ACME", "acme.admin"));

        assertThrows(IllegalStateException.class, TenantContext::require);
    }

    @Test
    void adminViewContainsNoPhoneNoEmailAndNoPickupPoint() throws Exception {
        MockHttpServletResponse response = call(BY_ID + "EMP-1001", tokenFor("ACME", "acme.admin"));

        assertEquals(Set.of("externalId", "name", "managerExternalId", "officeCode", "shiftCode", "status"),
                fieldNames(body(response)));
        String raw = response.getContentAsString();
        for (String personal : List.of("+919000001001", "emp1001@acme.example", "12.97", "77.75", "Demo Pickup Point A", "phone", "pickup")) {
            assertFalse(raw.contains(personal), "admin view exposed " + personal);
        }
    }

    @Test
    void operatorSeesTheSameRestrictedView() throws Exception {
        assertEquals(Set.of("externalId", "name", "managerExternalId", "officeCode", "shiftCode", "status"),
                fieldNames(body(call(BY_ID + "EMP-1001", tokenFor("ACME", "acme.operator")))));
    }

    @Test
    void unknownPathIsAProblemBody404() throws Exception {
        MockHttpServletResponse response = call("/api/v1/nothing-here", tokenFor("ACME", "acme.admin"));

        assertEquals(404, response.getStatus());
        assertEquals("NOT_FOUND", body(response).get("code").asText());
    }

    @Test
    void unknownPathWithoutATokenIs401() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/v1/nothing-here")).andReturn().getResponse().getStatus());
    }

    @Test
    void wrongMethodIsAProblemBody405() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/auth/login")).andReturn().getResponse();

        assertEquals(405, response.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", body(response).get("code").asText());
    }

    @Test
    void malformedLoginBodyIs400AndDoesNotEchoTheInput() throws Exception {
        String marker = "SecretMarkerValue";

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json").content("{\"password\": " + marker + "}"))
                .andReturn().getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        assertEquals("The request is invalid.", body(response).get("detail").asText());
        assertFalse(response.getContentAsString().contains(marker));
    }

    @Test
    void missingLoginFieldIs400AndNotA401() throws Exception {
        MockHttpServletResponse response = postLogin("ACME", "acme.admin", "").getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
    }

    @Test
    void personalDataIsNotLoggedEvenAtDebug(CapturedOutput output) throws Exception {
        call(ME, tokenFor("ACME", "acme.employee"));
        call(BY_ID + "EMP-1001", tokenFor("ACME", "acme.admin"));

        String log = output.getAll();
        for (String personal : List.of("+91900000", "@acme.example", "Demo Employee", "12.980000", "77.740000", "Demo Pickup Point", "Demo wheelchair")) {
            assertFalse(log.contains(personal), "the log contained " + personal);
        }
    }
}
