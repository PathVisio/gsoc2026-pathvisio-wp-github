package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonParser}.
 *
 * <p>These tests provide comprehensive coverage of JsonParser's static method
 * {@link JsonParser#extractValue(String, String)}, including:</p>
 * <ul>
 * <li>String value extraction with proper quote handling</li>
 * <li>Numeric and boolean value extraction from unquoted JSON fields</li>
 * <li>Null returns for missing or non-existent keys</li>
 * <li>Whitespace handling between JSON colons and values</li>
 * <li>Real-world GitHub API response payloads</li>
 * <li>Utility class design contract enforcement (non-instantiable constructor)</li>
 * </ul>
 *
 * <p>This test suite ensures JsonParser behaves correctly under all expected
 * conditions and serves as a specification for future maintenance and refactoring.</p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 */
@DisplayName("JsonParser")
class JsonParserTest {

    /**
     * Tests for extraction of quoted string values.
     */
    @Nested
    @DisplayName("String values")
    class StringValues {

        @Test
        @DisplayName("returns correct value for a string field")
        void returnsStringValue() {
            String json = "{\"user_code\":\"WBJI-BCAS\",\"interval\":5}";
            assertEquals("WBJI-BCAS", JsonParser.extractValue(json, "user_code"));
        }

        @Test
        @DisplayName("returns correct value for the last key in the object")
        void returnsLastStringValue() {
            String json = "{\"first\":\"alpha\",\"last\":\"omega\"}";
            assertEquals("omega", JsonParser.extractValue(json, "last"));
        }

        @Test
        @DisplayName("returns correct value when it is the only field")
        void returnsSingleField() {
            String json = "{\"device_code\":\"abc123\"}";
            assertEquals("abc123", JsonParser.extractValue(json, "device_code"));
        }

        @Test
        @DisplayName("returns an empty string for an empty quoted value")
        void returnsEmptyStringValue() {
            String json = "{\"error_description\":\"\"}";
            assertEquals("", JsonParser.extractValue(json, "error_description"));
        }
    }

    /**
     * Tests for extraction of unquoted numeric and boolean values.
     */
    @Nested
    @DisplayName("Numeric and boolean values")
    class NumericValues {

        @Test
        @DisplayName("returns numeric value as a string")
        void returnsNumericValue() {
            String json = "{\"interval\":5,\"expires_in\":900}";
            assertEquals("5", JsonParser.extractValue(json, "interval"));
        }

        @Test
        @DisplayName("returns the last numeric value in an object")
        void returnsLastNumericValue() {
            String json = "{\"interval\":5,\"expires_in\":900}";
            assertEquals("900", JsonParser.extractValue(json, "expires_in"));
        }

        @Test
        @DisplayName("returns integer that can be parsed back to int without error")
        void numericValueIsParseable() {
            String json = "{\"expires_in\":900}";
            String raw = JsonParser.extractValue(json, "expires_in");
            assertDoesNotThrow(() -> Integer.parseInt(raw));
            assertEquals(900, Integer.parseInt(raw));
        }
    }

    /**
     * Tests for handling of missing or non-existent keys, ensuring null is
     * returned appropriately and that key matching is precise (no partial matches).
     */
    @Nested
    @DisplayName("Missing keys")
    class MissingKeys {

        @Test
        @DisplayName("returns null when key is completely absent")
        void returnsNullForAbsentKey() {
            String json = "{\"device_code\":\"xyz\",\"interval\":5}";
            assertNull(JsonParser.extractValue(json, "user_code"));
        }

        @Test
        @DisplayName("returns null for an empty JSON object")
        void returnsNullForEmptyObject() {
            assertNull(JsonParser.extractValue("{}", "any_key"));
        }

        @Test
        @DisplayName("does not match a key that is a suffix of another key")
        void doesNotMatchSuffixKey() {
            String json = "{\"polling_interval\":30}";
            assertNull(JsonParser.extractValue(json, "interval"));
        }

        @Test
        @DisplayName("does not match a key that is a prefix of another key")
        void doesNotMatchPrefixKey() {
            String json = "{\"device_code\":\"abc\",\"user_code\":\"WBJI-BCAS\"}";
            assertNull(JsonParser.extractValue(json, "code"));
        }
    }

    /**
     * Tests for proper handling of whitespace between colons and values,
     * verifying that spaces, tabs, and newlines are correctly ignored.
     */
    @Nested
    @DisplayName("Whitespace handling")
    class WhitespaceHandling {

        @Test
        @DisplayName("handles a space between colon and string value")
        void handlesSpaceBeforeStringValue() {
            String json = "{\"user_code\": \"WBJI-BCAS\"}";
            assertEquals("WBJI-BCAS", JsonParser.extractValue(json, "user_code"));
        }

        @Test
        @DisplayName("handles a space between colon and numeric value")
        void handlesSpaceBeforeNumericValue() {
            String json = "{\"interval\": 5}";
            assertEquals("5", JsonParser.extractValue(json, "interval"));
        }

        @Test
        @DisplayName("handles tab and newline whitespace before value")
        void handlesTabAndNewlineWhitespace() {
            String json = "{\n\t\"interval\":\t5\n}";
            assertEquals("5", JsonParser.extractValue(json, "interval"));
        }
    }

    /**
     * Tests using real GitHub API response payloads to verify the parser's
     * capability with production data and serve as regression tests.
     */
    @Nested
    @DisplayName("Real GitHub API response shapes")
    class RealWorldPayloads {

        private static final String DEVICE_CODE_RESPONSE =
            "{" +
            "\"device_code\":\"YOUR-DEVICE-CODE\"," +
            "\"user_code\":\"WBJI-BCAS\"," +
            "\"verification_uri\":\"https://github.com/login/device\"," +
            "\"expires_in\":900," +
            "\"interval\":5" +
            "}";

        @Test
        @DisplayName("extracts device_code from real device-code response")
        void extractsDeviceCode() {
            assertEquals("YOUR-DEVICE-CODE",
                JsonParser.extractValue(DEVICE_CODE_RESPONSE, "device_code"));
        }

        @Test
        @DisplayName("extracts user_code from real device-code response")
        void extractsUserCode() {
            assertEquals("WBJI-BCAS",
                JsonParser.extractValue(DEVICE_CODE_RESPONSE, "user_code"));
        }

        @Test
        @DisplayName("extracts verification_uri from real device-code response")
        void extractsVerificationUri() {
            assertEquals("https://github.com/login/device",
                JsonParser.extractValue(DEVICE_CODE_RESPONSE, "verification_uri"));
        }

        @Test
        @DisplayName("extracts expires_in as parseable integer from real response")
        void extractsExpiresIn() {
            String raw = JsonParser.extractValue(DEVICE_CODE_RESPONSE, "expires_in");
            assertEquals(900, Integer.parseInt(raw));
        }

        @Test
        @DisplayName("extracts interval as parseable integer from real response")
        void extractsInterval() {
            String raw = JsonParser.extractValue(DEVICE_CODE_RESPONSE, "interval");
            assertEquals(5, Integer.parseInt(raw));
        }

        @Test
        @DisplayName("extracts access_token from successful token response")
        void extractsAccessToken() {
            String tokenResponse =
                "{\"access_token\":\"gho_abc123\"," +
                "\"token_type\":\"bearer\"," +
                "\"scope\":\"repo\"}";
            assertEquals("gho_abc123",
                JsonParser.extractValue(tokenResponse, "access_token"));
        }

        @Test
        @DisplayName("extracts error field from authorization_pending response")
        void extractsAuthorizationPendingError() {
            String pendingResponse = "{\"error\":\"authorization_pending\"," +
                "\"error_description\":\"The authorization request is still pending.\"}";
            assertEquals("authorization_pending",
                JsonParser.extractValue(pendingResponse, "error"));
        }

        @Test
        @DisplayName("extracts error field from slow_down response")
        void extractsSlowDownError() {
            String slowDown = "{\"error\":\"slow_down\"}";
            assertEquals("slow_down", JsonParser.extractValue(slowDown, "error"));
        }

        @Test
        @DisplayName("extracts error field from expired_token response")
        void extractsExpiredTokenError() {
            String expired = "{\"error\":\"expired_token\"}";
            assertEquals("expired_token", JsonParser.extractValue(expired, "error"));
        }
    }

    /**
     * Tests for the utility class design contract, verifying that the constructor
     * throws {@link UnsupportedOperationException} to prevent instantiation
     * and maintain the utility class pattern.
     */
    @Nested
    @DisplayName("Utility class design contract")
    class UtilityClassContract {

        @Test
        @DisplayName("constructor throws UnsupportedOperationException to prevent instantiation")
        void constructorThrowsToPreventInstantiation() throws Exception {
            java.lang.reflect.Constructor<JsonParser> constructor = JsonParser.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                constructor::newInstance
            );
            assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
        }
    }
}
