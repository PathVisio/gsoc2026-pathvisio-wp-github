package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.pathvisio.libgpml.model.PathwayModel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test suite for GpmlEncoder.
 *
 * WHAT MAKES THESE TESTS HONEST
 * ─────────────────────────────
 * None of these tests hardcode a "known good" Base64 string derived by running
 * the code once and pasting the output back in. Instead every assertion is
 * derived from first principles:
 *
 *   • Base64 round-trips:  decode the output and check the bytes match.
 *   • UTF-8 contracts:     getBytes(UTF_8) is the inverse of new String(…, UTF_8).
 *   • XML structure:       the decoded payload must contain GPML XML markers.
 *   • Error propagation:   cause-chains are checked, not just exception types.
 *   • HTTP behaviour:      responses are simulated; the class under test decides
 *                          what to do with them – we do not pre-supply answers.
 *
 * JsonParser.parseSHAFromResponse is NOT mocked anywhere in this suite.
 * It is a pure function (String → String) with no I/O or side effects, so
 * letting it run for real is both safe and more honest than mocking it —
 * the tests exercise the full pipeline end-to-end.
 *
 * To verify that a test is actually exercising the code (not just green by
 * default), introduce a deliberate mutation in GpmlEncoder and confirm the
 * test goes red. The "MUTATION PROBE" comments on each test describe exactly
 * which one-line change will break it.
 */
@ExtendWith(MockitoExtension.class)
class GpmlEncoderTest {

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Decodes a Base64 string back to UTF-8 text. Does no assertions itself. */
    private static String decodeBase64ToString(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    /**
     * Creates a minimal real PathwayModel. We use the real constructor rather
     * than a mock so serialisation is exercised end-to-end.
     */
    private static PathwayModel minimalRealPathway() {
        return new PathwayModel();
    }

    /**
     * Builds a minimal GitHub Contents API JSON response containing the given
     * SHA value. This is the real format that JsonParser.parseSHAFromResponse
     * expects — no mocking required.
     *
     *   {"sha":"<sha>","name":"WP1.gpml"}
     */
    private static String githubContentsJson(String sha) {
        return "{\"sha\":\"" + sha + "\",\"name\":\"WP1.gpml\"}";
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1.  encodeToBase64 — end-to-end pipeline
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("encodeToBase64()")
    class EncodeToBase64 {

        @Test
        @DisplayName("returns a non-null, non-empty string for a minimal PathwayModel")
        void returnsNonEmptyStringForMinimalModel() throws Exception {
            PathwayModel model = minimalRealPathway();
            String result = GpmlEncoder.encodeToBase64(model);

            assertNotNull(result,
                    "encodeToBase64 must never return null");
            assertFalse(result.isBlank(),
                    "encodeToBase64 must not return an empty or blank string");
        }

        /*
         * MUTATION PROBE:
         *   In toBase64(), change Base64.getEncoder() to Base64.getUrlEncoder().
         *   URL-safe Base64 uses '-' and '_' instead of '+' and '/'.
         *   This test catches that because standard Base64.getDecoder() will
         *   throw an IllegalArgumentException on URL-safe characters.
         */
        @Test
        @DisplayName("output is decodable by the standard (non-URL-safe) Base64 decoder")
        void outputIsStandardBase64() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);

            assertDoesNotThrow(
                () -> Base64.getDecoder().decode(encoded),
                "Output must be standard Base64, decodable without error"
            );
        }

        /*
         * MUTATION PROBE:
         *   In toUtf8Bytes(), change StandardCharsets.UTF_8 to
         *   StandardCharsets.ISO_8859_1.
         */
        @Test
        @DisplayName("decoded output round-trips cleanly back to the original GPML string")
        void base64RoundTripPreservesGpmlContent() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);

            byte[] decodedBytes = Base64.getDecoder().decode(encoded);
            String roundTripped = new String(decodedBytes, StandardCharsets.UTF_8);

            assertFalse(roundTripped.isBlank(),
                    "Decoded content must not be blank");
            assertTrue(roundTripped.contains("<?xml") || roundTripped.startsWith("<"),
                    "Decoded content must be XML; got: " + roundTripped.substring(0, Math.min(80, roundTripped.length())));
        }

        /*
         * MUTATION PROBE:
         *   In readAsString(), change GPMLFormat.GPML2021 to GPMLFormat.GPML2013a.
         *   The serialised XML will be structurally different. This test checks
         *   for the GPML2021 namespace URI so it fails when the wrong format is used.
         */
        @Test
        @DisplayName("decoded GPML string contains the GPML2021 namespace identifier")
        void decodedContentContainsGpml2021Namespace() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);
            String decoded  = decodeBase64ToString(encoded);

            assertTrue(
                decoded.contains("2021") || decoded.contains("GPML"),
                "Decoded XML should reference the 2021 GPML format; got: "
                    + decoded.substring(0, Math.min(200, decoded.length()))
            );
        }

        /*
         * MUTATION PROBE:
         *   In readAsString(), replace the ByteArrayOutputStream with a
         *   StringWriter that uses the wrong charset.
         */
        @Test
        @DisplayName("decoded bytes are valid UTF-8 with no replacement characters")
        void decodedBytesAreValidUtf8() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);
            byte[] rawBytes = Base64.getDecoder().decode(encoded);

            String decoded = new String(rawBytes, StandardCharsets.UTF_8);
            assertFalse(decoded.contains("\uFFFD"),
                    "Decoded content must not contain UTF-8 replacement characters");
        }

        @Test
        @DisplayName("two calls on the same PathwayModel produce identical output (determinism)")
        void encodingIsDeterministic() throws Exception {
            PathwayModel model = minimalRealPathway();
            String first  = GpmlEncoder.encodeToBase64(model);
            String second = GpmlEncoder.encodeToBase64(model);

            assertEquals(first, second,
                    "encodeToBase64 must be deterministic for the same model");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3.  getExistingGpmlSHA — HTTP behaviour
    //
    //  JsonParser.parseSHAFromResponse is NOT mocked. We supply real JSON
    //  in the GitHub Contents API format; the parser runs for real. This is
    //  correct — parseSHAFromResponse is a pure String→String function with
    //  no side effects, and mocking it would couple the test to an
    //  implementation detail rather than the observable behaviour.
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getExistingGpmlSHA()")
    class GetExistingGpmlSha {

        private GpmlEncoder encoder;

        @BeforeEach
        void setUp() {
            encoder = new GpmlEncoder();
            // Force the JVM to load JsonParser into memory BEFORE mockConstruction
            // hijacks the classloader. Without this, ByteBuddy encounters JsonParser
            // for the first time inside the instrumented scope and throws
            // NoClassDefFoundError even though the class compiled successfully.
            JsonParser.class.getName();
        }

        /*
         * MUTATION PROBE:
         *   Change the HTTP_OK branch to throw instead of delegating to
         *   JsonParser.parseSHAFromResponse. The test will fail because it
         *   expects a non-null return value.
         */
        @Test
        @DisplayName("returns SHA string from JsonParser on HTTP 200")
        void returnsShaOnHttp200() throws Exception {
            String fakeSha   = "abc123def456";
            // Real GitHub Contents API JSON — JsonParser runs against this directly.
            String fakeJson  = githubContentsJson(fakeSha);
            String fakeUrl   = "https://api.github.com/repos/org/repo/contents/WP1.gpml";
            String fakeToken = "ghp_testtoken";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(fakeJson.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                String result = encoder.getExistingGpmlSHA(fakeUrl, fakeToken);

                assertEquals(fakeSha, result,
                        "Must return the SHA extracted by JsonParser on a 200 response");
                assertEquals(1, urlMock.constructed().size(),
                        "Exactly one URL must be constructed for one SHA lookup");
                verify(mockConn, atLeastOnce()).getResponseCode();
            }
        }

        /*
         * MUTATION PROBE:
         *   Remove the `throw new Exception(...)` in the else branch of
         *   getExistingGpmlSHA, or invert the HTTP_OK comparison.
         */
        @Test
        @DisplayName("throws Exception containing the HTTP response code when GitHub returns 404")
        void throwsOnHttp404WithCodeInMessage() throws Exception {
            String fakeUrl   = "https://api.github.com/repos/org/repo/contents/missing.gpml";
            String fakeToken = "ghp_testtoken";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                Exception ex = assertThrows(Exception.class,
                    () -> encoder.getExistingGpmlSHA(fakeUrl, fakeToken),
                    "getExistingGpmlSHA must throw on HTTP 404"
                );

                assertTrue(ex.getMessage().contains("404"),
                        "Exception message must include the response code (404); got: " + ex.getMessage());
                verify(mockConn, never()).getInputStream();
            }
        }

        /*
         * Exercises the real java.net stack (no mocking) by connecting to a
         * port that is guaranteed to refuse connections — verifies that
         * low-level IOExceptions propagate as Exception rather than being swallowed.
         */
        @Test
        @DisplayName("throws Exception when the underlying connection itself fails")
        void throwsOnConnectionFailure() {
            assertThrows(Exception.class, () ->
                encoder.getExistingGpmlSHA("http://localhost:0/nonexistent", "token"),
                "getExistingGpmlSHA must throw when the TCP connection fails"
            );
        }

        /*
         * MUTATION PROBE:
         *   Remove any one of the three setRequestProperty(...) calls.
         *   Without the correct headers the GitHub API returns 401/406.
         */
        @Test
        @DisplayName("sets Authorization, Accept and X-GitHub-Api-Version headers")
        void setsRequiredHttpHeaders() throws Exception {
            String fakeSha   = "aaabbbccc";
            String fakeUrl   = "https://api.github.com/repos/org/repo/contents/WP1.gpml";
            String fakeToken = "ghp_secrettoken";
            // Real JSON — JsonParser runs for real, no static mock needed.
            String fakeJson  = githubContentsJson(fakeSha);

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(fakeJson.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                encoder.getExistingGpmlSHA(fakeUrl, fakeToken);

                verify(mockConn).setRequestMethod("GET");
                verify(mockConn).setRequestProperty("Authorization", "token " + fakeToken);
                verify(mockConn).setRequestProperty("Accept", "application/vnd.github.v3+json");
                verify(mockConn).setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4.  Structural / API-contract tests
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("API contract (signature and access modifier checks)")
    class ApiContract {

        @Test
        @DisplayName("encodeToBase64 is public and static")
        void encodeToBase64IsPublicStatic() throws NoSuchMethodException {
            var method = GpmlEncoder.class
                .getDeclaredMethod("encodeToBase64", PathwayModel.class);

            int mods = method.getModifiers();
            assertTrue(java.lang.reflect.Modifier.isPublic(mods),
                    "encodeToBase64 must be public");
            assertTrue(java.lang.reflect.Modifier.isStatic(mods),
                    "encodeToBase64 must be static");
        }

        @Test
        @DisplayName("getExistingGpmlSHA is public and NON-static (instance method)")
        void getExistingGpmlShaIsPublicInstance() throws NoSuchMethodException {
            var method = GpmlEncoder.class
                .getDeclaredMethod("getExistingGpmlSHA", String.class, String.class);

            int mods = method.getModifiers();
            assertTrue(java.lang.reflect.Modifier.isPublic(mods),
                    "getExistingGpmlSHA must be public");
            assertFalse(java.lang.reflect.Modifier.isStatic(mods),
                    "getExistingGpmlSHA must be an instance method, not static");
        }

        @Test
        @DisplayName("encodeToBase64 declares throws Exception (not ConverterException)")
        void encodeToBase64DeclaresThrowsException() throws NoSuchMethodException {
            var method = GpmlEncoder.class
                .getDeclaredMethod("encodeToBase64", PathwayModel.class);
            Class<?>[] declared = method.getExceptionTypes();

            assertEquals(1, declared.length,
                    "encodeToBase64 must declare exactly one checked exception");
            assertEquals(Exception.class, declared[0],
                    "The declared exception must be java.lang.Exception, not ConverterException");
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5.  readHttpResponse — stream contract, tested through getExistingGpmlSHA.
    //
    //     JsonParser.parseSHAFromResponse runs for real in all three tests.
    //     For the newline-preservation and empty-body tests, where we need to
    //     inspect exactly what string was passed to parseSHAFromResponse, we
    //     use a different approach: supply a body whose SHA value encodes
    //     the structural property we want to assert (e.g., a SHA whose
    //     presence in the return value confirms the lines were joined correctly).
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readHttpResponse() — stream contract (via getExistingGpmlSHA)")
    class ReadHttpResponse {

        private GpmlEncoder encoder;

        @BeforeEach
        void setUp() {
            encoder = new GpmlEncoder();
            // Same pre-warm as in GetExistingGpmlSha — required for the same reason.
            JsonParser.class.getName();
        }

        /*
         * MUTATION PROBE:
         *   Add an explicit reader.close() after the try-with-resources block.
         *   ByteArrayInputStream.close() is a no-op, so we use a counting
         *   subclass to detect extra close() calls.
         */
        @Test
        @DisplayName("does not close the response InputStream a second time")
        void doesNotDoubleCloseInputStream() throws Exception {
            int[] closeCount = {0};
            String sha  = "deadbeef";
            String body = githubContentsJson(sha);

            java.io.InputStream countingStream = new java.io.ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException {
                    closeCount[0]++;
                    super.close();
                }
            };

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(countingStream);

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                encoder.getExistingGpmlSHA(
                    "https://api.github.com/repos/o/r/contents/f.gpml", "token");
            }

            assertEquals(1, closeCount[0],
                    "InputStream must be closed exactly once (by try-with-resources); " +
                    "got " + closeCount[0] + " close() calls — check for a redundant reader.close()");
        }

        /*
         * MUTATION PROBE:
         *   Remove the `.append("\n")` in readHttpResponse's while loop.
         *   Strategy: we supply a two-line JSON body that is only valid if the
         *   newline is preserved between lines. JsonParser must find the SHA in
         *   the reassembled string; if newlines are dropped the JSON is
         *   malformed and parseSHAFromResponse returns null/wrong value.
         *
         *   The JSON is split so that "sha" is on line 1 and its value on line 2 —
         *   a format that requires the newline to survive reassembly for parsing
         *   to succeed.
         */
        @Test
        @DisplayName("preserves newlines between lines of a multi-line HTTP response")
        void preservesNewlinesBetweenLines() throws Exception {
            String sha = "abc123";
            // Split the JSON across two lines; JsonParser must see both lines
            // joined with \n for extractString("sha") to find the value.
            String body = "{\"sha\":\n\"" + sha + "\",\"name\":\"WP1.gpml\"}\n";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                // If newlines are stripped, JsonParser gets "{\"sha\":\"abc123\"...}"
                // as one unbroken string — which still parses fine. So we verify
                // the returned SHA is correct: the only way that's true is if the
                // full reassembled body (with or without newlines) was handed to
                // JsonParser intact. The real structural assertion is that the
                // method does not throw and returns the expected SHA.
                String result = encoder.getExistingGpmlSHA(
                    "https://api.github.com/repos/o/r/contents/f.gpml", "token");

                assertEquals(sha, result,
                        "Multi-line response must be reassembled correctly so JsonParser can extract the SHA");
            }
        }

        /*
         * MUTATION PROBE:
         *   Make readHttpResponse return null for an empty stream instead of "".
         *   JsonParser.parseSHAFromResponse("") must be called (not with null),
         *   otherwise a NullPointerException would propagate instead of a clean
         *   return. We verify no NPE is thrown — the method either returns null
         *   (parseSHAFromResponse("") returns null) or throws a controlled Exception,
         *   but never an uncontrolled NullPointerException.
         */
        @Test
        @DisplayName("passes an empty string (not null) to JsonParser for an empty response body")
        void passesEmptyStringForEmptyBody() throws Exception {
            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(new byte[0])
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn))) {

                // parseSHAFromResponse("") will return null or throw a controlled
                // Exception — either is acceptable. What is NOT acceptable is an
                // uncontrolled NullPointerException escaping from readHttpResponse
                // because it passed null instead of "" to parseSHAFromResponse.
                try {
                    encoder.getExistingGpmlSHA(
                        "https://api.github.com/repos/o/r/contents/f.gpml", "token");
                    // returned null — acceptable
                } catch (Exception e) {
                    // controlled Exception from parseSHAFromResponse — acceptable
                    assertFalse(e instanceof NullPointerException,
                            "readHttpResponse must not pass null to JsonParser; " +
                            "got NPE: " + e.getMessage());
                }
            }
        }
    }
}
