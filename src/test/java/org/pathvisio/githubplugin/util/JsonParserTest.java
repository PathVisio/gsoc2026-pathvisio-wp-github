package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JsonParser}.
 *
 * <p>These tests cover the full contract of JsonParser's static method
 * {@link JsonParser#extractValue(String, String)}, including:</p>
 * <ul>
 * <li>Happy-path cases for string and numeric values</li>
 * <li>Key-not-found cases that should return null</li>
 * <li>Whitespace handling between the colon and value</li>
 * <li>Real-world JSON payloads from GitHub's API</li>
 * <li>The utility class design contract (constructor throws to prevent instantiation)</li>
 * </ul>
 *
 * <p>By covering all these cases, we ensure that JsonParser behaves correctly
 * under all expected conditions and that any future changes to the code will
 * be verified against this comprehensive specification.</p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 */
@DisplayName("JsonParser")
class JsonParserTest {

    // =========================================================================
    // SECTION 1 — String values
    //
    // This section covers the branch of the parser that handles quoted string values.          
    // =========================================================================

    @Nested
    @DisplayName("String values")
    class StringValues {

        @Test
        @DisplayName("returns correct value for a string field")
        void returnsStringValue() {
            // This is the exact example from the class Javadoc — a good anchor test.
            String json = "{\"user_code\":\"WBJI-BCAS\",\"interval\":5}";
            assertEquals("WBJI-BCAS", JsonParser.extractValue(json, "user_code"));
        }

        @Test
        @DisplayName("returns correct value for the last key in the object")
        void returnsLastStringValue() {
            // The last field has no trailing comma — the parser must fall back to '}'
            // to find the end of the value. A common off-by-one mistake.
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
            // TEACHING POINT: this is a realistic edge case.
            // GitHub could theoretically return "" for an optional field.
            // Does the parser return "" or null? The contract says return
            // the value — so "" is correct. If it returns null, that's a bug
            // that would cause a NullPointerException in the caller.
            String json = "{\"error_description\":\"\"}";
            assertEquals("", JsonParser.extractValue(json, "error_description"));
        }
    }

    // =========================================================================
    // SECTION 2 — Numeric / boolean (unquoted) values
    //
    // Unquoted values follow a different code path in the parser (no '"' branch).
    // They deserve their own tests.
    // =========================================================================

    @Nested
    @DisplayName("Numeric and boolean values")
    class NumericValues {

        @Test
        @DisplayName("returns numeric value as a string")
        void returnsNumericValue() {
            // This is the second example from the Javadoc.
            String json = "{\"interval\":5,\"expires_in\":900}";
            assertEquals("5", JsonParser.extractValue(json, "interval"));
        }

        @Test
        @DisplayName("returns the last numeric value in an object")
        void returnsLastNumericValue() {
            // Same trailing-comma issue as for strings, but for the numeric branch.
            // The parser looks for ',' first, then '}'. If it doesn't find '}',
            // it returns null — so this verifies the fallback works for numbers too.
            String json = "{\"interval\":5,\"expires_in\":900}";
            assertEquals("900", JsonParser.extractValue(json, "expires_in"));
        }

        @Test
        @DisplayName("returns integer that can be parsed back to int without error")
        void numericValueIsParseable() {
            // TEACHING POINT: tests can also verify downstream usability,
            // not just raw return values. This mirrors how the real code uses
            // the result: Integer.parseInt(intervalStr).
            String json = "{\"expires_in\":900}";
            String raw = JsonParser.extractValue(json, "expires_in");
            assertDoesNotThrow(() -> Integer.parseInt(raw));
            assertEquals(900, Integer.parseInt(raw));
        }
    }

    // =========================================================================
    // SECTION 3 — Key-not-found cases
    //
    // Every null-returning path in the parser should be exercised.
    // These protect against callers getting a NullPointerException because
    // they assumed the key would always be present.
    // =========================================================================

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

        // TEACHING POINT — the "partial match" trap
        // The parser uses json.indexOf("\"interval\":") which is exact, but a naive
        // implementation might use contains("interval") and accidentally match
        // "polling_interval". This test verifies key matching is exact.
        @Test
        @DisplayName("does not match a key that is a suffix of another key")
        void doesNotMatchSuffixKey() {
            // "interval" must NOT match "polling_interval"
            String json = "{\"polling_interval\":30}";
            assertNull(JsonParser.extractValue(json, "interval"));
        }

        @Test
        @DisplayName("does not match a key that is a prefix of another key")
        void doesNotMatchPrefixKey() {
            // "code" must NOT match "device_code" or "user_code"
            String json = "{\"device_code\":\"abc\",\"user_code\":\"WBJI-BCAS\"}";
            assertNull(JsonParser.extractValue(json, "code"));
        }
    }

    // =========================================================================
    // SECTION 4 — Whitespace handling
    //
    // The Javadoc explicitly says "whitespace between the colon and value
    // is ignored". That is a stated contract — so we must test it.
    // =========================================================================

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
            // Pretty-printed JSON from some API clients uses newlines and tabs.
            String json = "{\n\t\"interval\":\t5\n}";
            assertEquals("5", JsonParser.extractValue(json, "interval"));
        }
    }

    // =========================================================================
    // SECTION 5 — Real GitHub API response shapes
    //
    // TEACHING POINT: "real-world integration tests" within a unit test file.
    // These use the actual JSON payloads described in the GSoC proposal.
    // If the parser can handle these, it will handle production data.
    // They also double as regression tests: if someone changes the parser
    // and these break, you know you've broken real behaviour.
    // =========================================================================

    @Nested
    @DisplayName("Real GitHub API response shapes")
    class RealWorldPayloads {

        // The exact shape from section 4.1 of the proposal
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
            // Shape of a successful /oauth/access_token response
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
            // Shape of a "still waiting" poll response
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

    // =========================================================================
    // SECTION 6 — The "utility class cannot be instantiated" contract
    //
    // TEACHING POINT: testing a design contract, not just a computation.
    // The constructor throws UnsupportedOperationException by design.
    // This test proves that contract is enforced and documents it for readers.
    // Without this, someone could accidentally remove the private constructor
    // and the class becomes instantiable — a silent API regression.
    // =========================================================================

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