package com.moveinsync.roster.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.core.validation.ErrorCode;
import com.moveinsync.roster.core.validation.ValidationError;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static IngestEvent parse(String json) throws Exception {
        return IngestEvent.parse(MAPPER.readTree(json));
    }

    private static List<ErrorCode> codes(IngestEvent event) {
        return event.validate().stream().map(ValidationError::code).toList();
    }

    @Test
    void readsAWellFormedEvent() throws Exception {
        IngestEvent event = parse("""
                {"eventId":"e1","employeeExternalId":"EMP-1","source":"HR","eventType":"UPDATE","version":7,
                 "occurredAt":"2026-10-30T02:14:11Z","payload":{"name":"N","officeCode":"BLR-WHF"}}""");

        assertEquals("e1", event.eventId());
        assertEquals(7L, event.version());
        assertEquals(Instant.parse("2026-10-30T02:14:11Z"), event.occurredAt());
        assertEquals(Set.of(FieldKey.NAME, FieldKey.OFFICE_CODE), event.presentFields());
        assertTrue(event.validate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "null", "\"text\"", "[]", "{}"})
    void somethingThatIsNotAnEventBecomesErrorsNotAnException(String json) throws Exception {
        assertTrue(codes(parse(json)).containsAll(List.of(ErrorCode.MISSING_EVENT_ID, ErrorCode.MISSING_EXTERNAL_ID,
                ErrorCode.INVALID_VERSION, ErrorCode.INVALID_SOURCE, ErrorCode.INVALID_EVENT_TYPE)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7\"", "7.5", "true", "[7]", "99999999999999999999"})
    void aVersionThatIsNotAWholeNumberIsInvalid(String version) throws Exception {
        IngestEvent event = parse("{\"eventId\":\"e\",\"employeeExternalId\":\"E\",\"source\":\"HR\",\"eventType\":\"UPDATE\",\"version\":" + version + "}");

        assertNull(event.version());
        assertEquals(List.of(ErrorCode.INVALID_VERSION), codes(event));
    }

    @Test
    void anAbsentFieldIsNotPresentButAnExplicitNullIs() throws Exception {
        IngestEvent event = parse("{\"payload\":{\"shiftCode\":null}}");

        assertEquals(Set.of(FieldKey.SHIFT_CODE), event.presentFields());
        assertNull(event.text(FieldKey.SHIFT_CODE));
    }

    @Test
    void aNonNumericCoordinateIsAnErrorForTheOwnerOnly() throws Exception {
        String body = "\"employeeExternalId\":\"E\",\"eventId\":\"e\",\"eventType\":\"UPDATE\",\"version\":1,\"payload\":{\"pickupLat\":\"north\"}}";

        assertEquals(List.of(ErrorCode.INVALID_COORDINATE), codes(parse("{\"source\":\"TRANSPORT\"," + body)));
        assertTrue(codes(parse("{\"source\":\"HR\"," + body)).isEmpty());
        assertEquals(Set.of(FieldKey.PICKUP_LAT), parse("{\"source\":\"HR\"," + body).presentFields());
    }

    @Test
    void readsANumericCoordinate() throws Exception {
        assertEquals(0, new BigDecimal("12.97").compareTo(parse("{\"payload\":{\"pickupLat\":12.97}}").decimal(FieldKey.PICKUP_LAT)));
    }

    @Test
    void aDeactivationIgnoresItsPayload() throws Exception {
        IngestEvent event = parse("""
                {"eventId":"e","employeeExternalId":"E","source":"HR","eventType":"DEACTIVATE","version":1,
                 "payload":{"phone":"not-a-phone","name":"N"}}""");

        assertEquals(Set.of(FieldKey.STATUS), event.presentFields());
        assertTrue(event.validate().isEmpty());
    }

    @Test
    void aPayloadStatusIsNotAField() throws Exception {
        assertTrue(parse("{\"payload\":{\"status\":\"INACTIVE\"}}").presentFields().isEmpty());
    }

    @Test
    void anUnreadableOccurredAtIsNotAnError() throws Exception {
        assertNull(parse("{\"occurredAt\":\"yesterday\"}").occurredAt());
    }

    @Test
    void toStringPrintsIdsOnly() throws Exception {
        String printed = parse("""
                {"eventId":"e1","employeeExternalId":"EMP-1","payload":{"name":"Secret Name","phone":"+919000000000","pickupLat":12.97}}""").toString();

        assertTrue(printed.contains("EMP-1"));
        for (String personal : List.of("Secret Name", "+919000000000", "12.97")) {
            assertFalse(printed.contains(personal));
        }
    }
}
