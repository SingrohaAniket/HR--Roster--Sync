package com.moveinsync.roster.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordValidatorTest {

    private static final String VALID_PHONE = "+919000001001";
    private static final String VALID_EMAIL = "emp1001@acme.example";

    private static RosterRecord hrUpdate(String eventId, String externalId, Long version) {
        return new RosterRecord(eventId, externalId, version, "HR", "UPDATE", null, null, null, null, null, null, null, null, null, null);
    }

    private static RosterRecord withSourceAndType(String source, String eventType) {
        return new RosterRecord("evt-1", "EMP-1001", 7L, source, eventType, null, null, null, null, null, null, null, null, null, null);
    }

    private static RosterRecord withPhone(String source, String phone) {
        return new RosterRecord("evt-1", "EMP-1001", 7L, source, "UPDATE", null, null, phone, null, null, null, null, null, null, null);
    }

    private static RosterRecord withEmail(String source, String email) {
        return new RosterRecord("evt-1", "EMP-1001", 7L, source, "UPDATE", null, email, null, null, null, null, null, null, null, null);
    }

    private static RosterRecord withCoordinates(String source, String lat, String lng) {
        return new RosterRecord("evt-1", "EMP-1001", 7L, source, "UPDATE", null, null, null, null,
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng), null, null, null, null);
    }

    private static RosterRecord create(String name, String email, String officeCode) {
        return new RosterRecord("evt-1", "EMP-1001", 1L, "HR", "CREATE", name, email, null, officeCode, null, null, null, null, null, null);
    }

    private static void assertOnlyError(ErrorCode code, ValidatedField field, RosterRecord record) {
        assertEquals(List.of(new ValidationError(code, field)), RecordValidator.validate(record));
    }

    @Test
    void validRecordProducesNoErrors() {
        RosterRecord record = new RosterRecord("hr-prod-0001", "EMP-1001", 7L, "HR", "CREATE",
                "Demo Employee One", VALID_EMAIL, VALID_PHONE, "BLR-WHF", null, null, null, null, null, null);

        assertTrue(RecordValidator.validate(record).isEmpty());
    }

    @Test
    void validTransportRecordProducesNoErrors() {
        assertTrue(RecordValidator.validate(withCoordinates("TRANSPORT", "12.97", "77.75")).isEmpty());
    }

    @Test
    void deactivateNeedsNoPayloadFields() {
        assertTrue(RecordValidator.validate(withSourceAndType("HR", "DEACTIVATE")).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingEventIdIsRejected(String eventId) {
        assertOnlyError(ErrorCode.MISSING_EVENT_ID, ValidatedField.EVENT_ID, hrUpdate(eventId, "EMP-1001", 7L));
    }

    @Test
    void eventIdOverOneHundredCharsIsRejected() {
        assertOnlyError(ErrorCode.MISSING_EVENT_ID, ValidatedField.EVENT_ID, hrUpdate("e".repeat(101), "EMP-1001", 7L));
        assertTrue(RecordValidator.validate(hrUpdate("e".repeat(100), "EMP-1001", 7L)).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingExternalIdIsRejected(String externalId) {
        assertOnlyError(ErrorCode.MISSING_EXTERNAL_ID, ValidatedField.EMPLOYEE_EXTERNAL_ID, hrUpdate("evt-1", externalId, 7L));
    }

    @Test
    void externalIdOverSixtyFourCharsIsRejected() {
        assertOnlyError(ErrorCode.MISSING_EXTERNAL_ID, ValidatedField.EMPLOYEE_EXTERNAL_ID, hrUpdate("evt-1", "x".repeat(65), 7L));
        assertTrue(RecordValidator.validate(hrUpdate("evt-1", "x".repeat(64), 7L)).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void versionMissingOrBelowOneIsRejected(Long version) {
        assertOnlyError(ErrorCode.INVALID_VERSION, ValidatedField.VERSION, hrUpdate("evt-1", "EMP-1001", version));
    }

    @Test
    void versionOneIsAccepted() {
        assertTrue(RecordValidator.validate(hrUpdate("evt-1", "EMP-1001", 1L)).isEmpty());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "PAYROLL", "hr"})
    void unknownSourceIsRejected(String source) {
        assertOnlyError(ErrorCode.INVALID_SOURCE, ValidatedField.SOURCE, withSourceAndType(source, "UPDATE"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "DELETE", "update"})
    void unknownEventTypeIsRejected(String eventType) {
        assertOnlyError(ErrorCode.INVALID_EVENT_TYPE, ValidatedField.EVENT_TYPE, withSourceAndType("HR", eventType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "919000001001", "+019000001001", "+9198765", "+91 9000001001", "+91900000100a"})
    void malformedPhoneIsRejected(String phone) {
        assertOnlyError(ErrorCode.INVALID_PHONE, ValidatedField.PHONE, withPhone("HR", phone));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+12345678", "+123456789012345", VALID_PHONE})
    void phoneAtTheE164LimitsIsAccepted(String phone) {
        assertTrue(RecordValidator.validate(withPhone("HR", phone)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "two@@acme.example", "@acme.example", "user@", "user@nodot", "has space@acme.example"})
    void malformedEmailIsRejected(String email) {
        assertOnlyError(ErrorCode.INVALID_EMAIL, ValidatedField.EMAIL, withEmail("HR", email));
    }

    @Test
    void emailLongerThanTheColumnIsTooLongRatherThanMalformed() {
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.EMAIL, withEmail("HR", "a".repeat(320) + "@acme.example"));
    }

    @Test
    void validEmailIsAccepted() {
        assertTrue(RecordValidator.validate(withEmail("HR", VALID_EMAIL)).isEmpty());
    }

    private static RosterRecord withLongFields(String source, String name, String manager, String shift, String label, String assistance) {
        return new RosterRecord("evt-1", "EMP-1001", 7L, source, "UPDATE", name, null, null, null, null, null, manager, shift, label, assistance);
    }

    @Test
    void valuesAtTheColumnWidthAreAccepted() {
        assertTrue(RecordValidator.validate(withLongFields("HR", "n".repeat(255), "m".repeat(64), "s".repeat(32), null, null)).isEmpty());
        assertTrue(RecordValidator.validate(withLongFields("TRANSPORT", null, null, null, "l".repeat(255), "a".repeat(500))).isEmpty());
        assertTrue(RecordValidator.validate(withEmail("HR", "a".repeat(307) + "@acme.example")).isEmpty());
    }

    @Test
    void eachFieldOverItsColumnWidthNamesThatField() {
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.NAME, withLongFields("HR", "n".repeat(256), null, null, null, null));
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.MANAGER_EXTERNAL_ID, withLongFields("HR", null, "m".repeat(65), null, null, null));
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.SHIFT_CODE, withLongFields("HR", null, null, "s".repeat(33), null, null));
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.PICKUP_LABEL, withLongFields("TRANSPORT", null, null, null, "l".repeat(256), null));
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.SPECIAL_ASSISTANCE, withLongFields("TRANSPORT", null, null, null, null, "a".repeat(501)));
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.PHONE, withPhone("HR", "+1234567890123456"));
    }

    @Test
    void anOverLongFieldTheSenderDoesNotOwnIsNotRejected() {
        assertTrue(RecordValidator.validate(withLongFields("TRANSPORT", "n".repeat(300), null, "s".repeat(40), null, null)).isEmpty());
        assertTrue(RecordValidator.validate(withLongFields("HR", null, null, null, "l".repeat(300), "a".repeat(600))).isEmpty());
    }

    @Test
    void widthIsCountedInCharactersTheWayTheColumnCountsThem() {
        String emoji = new String(Character.toChars(0x1F600));

        assertTrue(RecordValidator.validate(withLongFields("HR", emoji.repeat(255), null, null, null, null)).isEmpty());
        assertOnlyError(ErrorCode.FIELD_TOO_LONG, ValidatedField.NAME, withLongFields("HR", emoji.repeat(256), null, null, null, null));
    }

    @Test
    void createWithoutNameIsRejected() {
        assertOnlyError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.NAME, create(null, VALID_EMAIL, "BLR-WHF"));
    }

    @Test
    void createWithoutEmailIsRejected() {
        assertOnlyError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.EMAIL, create("Demo", null, "BLR-WHF"));
    }

    @Test
    void createWithoutOfficeCodeIsRejected() {
        assertOnlyError(ErrorCode.INCOMPLETE_CREATE, ValidatedField.OFFICE_CODE, create("Demo", VALID_EMAIL, " "));
    }

    @Test
    void updateDoesNotNeedTheCreateFields() {
        assertTrue(RecordValidator.validate(hrUpdate("evt-1", "EMP-1001", 7L)).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"90.000001", "-90.000001"})
    void latitudeOutOfRangeIsRejected(String lat) {
        assertOnlyError(ErrorCode.INVALID_COORDINATE, ValidatedField.PICKUP_LAT, withCoordinates("TRANSPORT", lat, null));
    }

    @ParameterizedTest
    @CsvSource({"180.000001", "-180.000001"})
    void longitudeOutOfRangeIsRejected(String lng) {
        assertOnlyError(ErrorCode.INVALID_COORDINATE, ValidatedField.PICKUP_LNG, withCoordinates("TRANSPORT", null, lng));
    }

    @Test
    void coordinatesAtTheLimitsAreAccepted() {
        assertTrue(RecordValidator.validate(withCoordinates("TRANSPORT", "90", "180")).isEmpty());
        assertTrue(RecordValidator.validate(withCoordinates("TRANSPORT", "-90", "-180")).isEmpty());
    }

    @Test
    void officeExistenceIsNotCheckedHere() {
        assertTrue(RecordValidator.validate(create("Demo", VALID_EMAIL, "NO-SUCH-OFFICE")).isEmpty());
    }

    @Test
    void hrPayloadWithABadPickupPointIsNotRejected() {
        assertTrue(RecordValidator.validate(withCoordinates("HR", "999", "999")).isEmpty());
    }

    @Test
    void transportPayloadWithABadPhoneOrEmailIsNotRejected() {
        assertTrue(RecordValidator.validate(withPhone("TRANSPORT", "not-a-phone")).isEmpty());
        assertTrue(RecordValidator.validate(withEmail("TRANSPORT", "not-an-email")).isEmpty());
    }

    @Test
    void withAnUnknownSourceEveryFieldIsChecked() {
        List<ValidationError> errors = RecordValidator.validate(withPhone("PAYROLL", "not-a-phone"));

        assertEquals(List.of(
                new ValidationError(ErrorCode.INVALID_SOURCE, ValidatedField.SOURCE),
                new ValidationError(ErrorCode.INVALID_PHONE, ValidatedField.PHONE)), errors);
    }

    @Test
    void reportsEveryProblemInOneRecord() {
        RosterRecord record = new RosterRecord(null, null, 0L, "PAYROLL", "DELETE", null, null, null, null, null, null, null, null, null, null);

        assertEquals(List.of(ErrorCode.MISSING_EVENT_ID, ErrorCode.MISSING_EXTERNAL_ID, ErrorCode.INVALID_VERSION,
                        ErrorCode.INVALID_SOURCE, ErrorCode.INVALID_EVENT_TYPE),
                RecordValidator.validate(record).stream().map(ValidationError::code).toList());
    }

    @Test
    void errorsCarryTheFieldNameAndNeverTheValue() {
        String badPhone = "+9198765";

        ValidationError error = RecordValidator.validate(withPhone("HR", badPhone)).get(0);

        assertEquals("phone", error.field().apiName());
        assertFalse(error.toString().contains(badPhone));
    }

    @Test
    void validationErrorHasNoComponentThatCouldHoldAValue() {
        for (var component : ValidationError.class.getRecordComponents()) {
            assertTrue(component.getType().isEnum(), component.getName() + " must be a closed enum");
        }
    }

    @Test
    void recordToStringPrintsIdsOnly() {
        RosterRecord record = new RosterRecord("evt-1", "EMP-1001", 7L, "HR", "UPDATE", "Demo Name", VALID_EMAIL, VALID_PHONE,
                "BLR-WHF", new BigDecimal("12.97"), new BigDecimal("77.75"), null, null, null, null);

        String printed = record.toString();

        assertTrue(printed.contains("EMP-1001"));
        for (String personal : List.of("Demo Name", VALID_EMAIL, VALID_PHONE, "12.97", "77.75")) {
            assertFalse(printed.contains(personal), "toString leaked a personal field");
        }
    }
}
