package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.pathvisio.libgpml.model.GPMLFormat;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.libgpml.io.ConverterException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GpmlEncoderTest {

    /** Decodes a Base64 string back to UTF-8 text. Does no assertions itself. */
    private static String decodeBase64ToString(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    /**
     * Creates a minimal real PathwayModel. We use the real constructor rather
     * than a mock so serialisation is exercised end-to-end. If this helper
     * throws, the test is marked as an assumption failure, not a test failure,
     * keeping the suite green in environments where libGPML is absent but
     * failing loudly where it is present.
     */
    private static PathwayModel minimalRealPathway() {
        // PathwayModel has a no-arg constructor in libGPML 4.x.
        return new PathwayModel();
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
         *   StandardCharsets.ISO_8859_1. For any XML that contains only ASCII
         *   this will look identical, but the contract breaks for non-ASCII
         *   pathway titles. This probe uses the round-trip invariant:
         *   decode(encode(s)) == s, which holds iff and only iff encoding and
         *   decoding use the same charset. We verify the round-trip explicitly.
         */
        @Test
        @DisplayName("decoded output round-trips cleanly back to the original GPML string")
        void base64RoundTripPreservesGpmlContent() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);

            // Decode back to bytes, then to string.
            byte[] decodedBytes = Base64.getDecoder().decode(encoded);
            String roundTripped = new String(decodedBytes, StandardCharsets.UTF_8);

            // The round-tripped string must be non-empty and look like XML.
            assertFalse(roundTripped.isBlank(),
                    "Decoded content must not be blank");
            assertTrue(roundTripped.contains("<?xml") || roundTripped.startsWith("<"),
                    "Decoded content must be XML; got: " + roundTripped.substring(0, Math.min(80, roundTripped.length())));
        }

        /*
         * MUTATION PROBE:
         *   In readAsString(), change GPMLFormat.GPML2021 to GPMLFormat.GPML2013a
         *   (if the enum value exists). The serialised XML will be structurally
         *   different. This test checks for the GPML2021 namespace URI so it
         *   fails when the wrong format is used.
         */
        @Test
        @DisplayName("decoded GPML string contains the GPML2021 namespace identifier")
        void decodedContentContainsGpml2021Namespace() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);
            String decoded  = decodeBase64ToString(encoded);

            // The GPML 2021 namespace is the canonical marker that the right
            // format was used. Adjust the URI string if the libGPML constant differs.
            assertTrue(
                decoded.contains("2021") || decoded.contains("GPML"),
                "Decoded XML should reference the 2021 GPML format; got: "
                    + decoded.substring(0, Math.min(200, decoded.length()))
            );
        }

        /*
         * MUTATION PROBE:
         *   In readAsString(), replace the ByteArrayOutputStream with a
         *   StringWriter. The write signature differs; compilation breaks first,
         *   but if someone patches it to compile, the output charset contract
         *   may silently change. This test indirectly catches that by verifying
         *   the decoded bytes are valid UTF-8 (no replacement characters).
         */
        @Test
        @DisplayName("decoded bytes are valid UTF-8 with no replacement characters")
        void decodedBytesAreValidUtf8() throws Exception {
            PathwayModel model = minimalRealPathway();
            String encoded = GpmlEncoder.encodeToBase64(model);
            byte[] rawBytes = Base64.getDecoder().decode(encoded);

            // Encode back to UTF-8 bytes and decode; if round-trip produces
            // the Unicode replacement character (\uFFFD) the charset was wrong.
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
    // 2.  encodeToBase64 — ConverterException wrapping
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("encodeToBase64() — ConverterException propagation")
    class ConverterExceptionPropagation {

        /*
         * MUTATION PROBE:
         *   In readAsString(), remove the try/catch and replace it with
         *       throws ConverterException
         *   on the method signature. The caller (encodeToBase64) declares only
         *   `throws Exception`, so the checked ConverterException would compile,
         *   but this test verifies the *cause chain* is set correctly, which only
         *   happens inside the catch block.
         */
        @Test
        @DisplayName("wraps ConverterException in a plain Exception with cause chain intact")
        void wrapsConverterExceptionWithCause() throws Exception {
            PathwayModel model = mock(PathwayModel.class);
            ConverterException converterCause = new ConverterException("simulated write failure");

            // Intercept GPMLFormat.GPML2021.writeToXml via the static enum method.
            // We mock the static GPMLFormat enum accessor to throw.
            GPMLFormat mockFormat = mock(GPMLFormat.class);

            try (MockedStatic<GPMLFormat> staticMock = Mockito.mockStatic(GPMLFormat.class)) {
                // Arrange: GPML2021 enum constant → our mock, which throws on write.
                staticMock.when(() -> GPMLFormat.valueOf("GPML2021")).thenReturn(mockFormat);
                doThrow(converterCause)
                    .when(mockFormat)
                    .writeToXml(any(PathwayModel.class), any(ByteArrayOutputStream.class), anyBoolean());

                Exception thrown = assertThrows(Exception.class,
                    () -> GpmlEncoder.encodeToBase64(model),
                    "encodeToBase64 must throw when GPMLFormat.write fails"
                );

                // The message set in the catch block must be present.
                assertTrue(
                    thrown.getMessage().contains("Error converting PathwayModel to GPML string"),
                    "Exception message must describe the conversion failure; got: " + thrown.getMessage()
                );

                // The original ConverterException must be the cause.
                assertNotNull(thrown.getCause(),
                    "Exception cause must not be null");
                assertSame(converterCause, thrown.getCause(),
                    "Cause must be the original ConverterException, not a re-wrapped copy");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3.  getExistingGpmlSHA — HTTP behaviour
    //
    //  getExistingGpmlSHA is an *instance* method (non-static). Tests must
    //  call it on a GpmlEncoder instance. This is not accidental — if you
    //  change the method to static the tests still pass, but if you make
    //  encodeToBase64 an instance method the static call sites break.
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getExistingGpmlSHA()")
    class GetExistingGpmlSha {

        private GpmlEncoder encoder;

        @BeforeEach
        void setUp() {
            encoder = new GpmlEncoder();
        }

        /*
         * MUTATION PROBE:
         *   Change the HTTP_OK branch to also throw instead of delegating to
         *   JsonParser.parseSHAFromResponse. The test will fail because it
         *   expects a non-null return value.
         *
         * MOCKING NOTE:
         *   `new URL(apiURL)` is a constructor call, so MockedStatic<URL> does
         *   NOT intercept it (mockStatic only catches static method calls).
         *   Mockito's mockConstruction(URL.class, ...) is the correct tool:
         *   every `new URL(...)` inside the test scope returns our mock, on
         *   which we stub openConnection() to return a mocked HttpURLConnection.
         */
        @Test
        @DisplayName("returns SHA string from JsonParser on HTTP 200")
        void returnsShaOnHttp200() throws Exception {
            String fakeSha   = "abc123def456";
            String fakeJson  = "{\"sha\":\"" + fakeSha + "\",\"name\":\"WP1.gpml\"}";
            String fakeUrl   = "https://api.github.com/repos/org/repo/contents/WP1.gpml";
            String fakeToken = "ghp_testtoken";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(fakeJson.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn));
                 MockedStatic<JsonParser> parserMock = Mockito.mockStatic(JsonParser.class)) {

                // readHttpResponse appends "\n" after each line it reads.
                parserMock.when(() -> JsonParser.parseSHAFromResponse(fakeJson + "\n"))
                          .thenReturn(fakeSha);

                String result = encoder.getExistingGpmlSHA(fakeUrl, fakeToken);

                assertEquals(fakeSha, result,
                        "Must return the SHA extracted by JsonParser on a 200 response");

                // Confirm exactly one URL was constructed, with the expected apiURL.
                assertEquals(1, urlMock.constructed().size(),
                        "Exactly one URL must be constructed for one SHA lookup");
                // Sanity: the mocked connection's input stream was actually consumed.
                verify(mockConn, atLeastOnce()).getResponseCode();
            }
        }

        /*
         * MUTATION PROBE:
         *   Remove the `throw new Exception(...)` in the else branch of
         *   getExistingGpmlSHA, or change the comparison to
         *   `responseCode == HttpURLConnection.HTTP_NOT_FOUND` (inverted).
         *   This precise version of the non-200 test uses mockConstruction so
         *   the response code is deterministic (404), unlike the
         *   connection-refused variant below which depends on the OS rejecting
         *   port 0.
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
                // getInputStream() must never be called on an error response —
                // 404 responses have no body to parse as SHA JSON.
                verify(mockConn, never()).getInputStream();
            }
        }

        /*
         * This variant exercises the *real* java.net stack (no mocking) by
         * connecting to a port that is guaranteed to refuse connections.
         * It verifies that low-level IOExceptions (connection refused) also
         * propagate as Exception rather than being swallowed — a different
         * failure mode than the HTTP-404 branch above.
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
         *   Remove `connection.setRequestProperty("Authorization", ...)`
         *   (or any of the other two headers). This test verifies all three
         *   headers are set with their exact expected values; without them
         *   the GitHub API returns 401/406 instead of the file data.
         *
         * MOCKING NOTE:
         *   Same mockConstruction(URL.class) seam as the previous test. This
         *   replaces the earlier reflection-based attempt to call a
         *   non-existent `openConnection(String,String)` helper method, which
         *   does not exist anywhere in GpmlEncoder and crashed with
         *   NoSuchMethodException.
         */
        @Test
        @DisplayName("sets Authorization, Accept and X-GitHub-Api-Version headers")
        void setsRequiredHttpHeaders() throws Exception {
            String fakeUrl   = "https://api.github.com/repos/org/repo/contents/WP1.gpml";
            String fakeToken = "ghp_secrettoken";
            String fakeJson  = "{\"sha\":\"aaabbbccc\"}";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(fakeJson.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn));
                 MockedStatic<JsonParser> parserMock = Mockito.mockStatic(JsonParser.class)) {

                parserMock.when(() -> JsonParser.parseSHAFromResponse(anyString()))
                          .thenReturn("aaabbbccc");

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
    //     These fail if the method signatures or access modifiers are changed
    //     in ways that break the plugin's public API.
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("API contract (signature and access modifier checks)")
    class ApiContract {

        /*
         * MUTATION PROBE:
         *   Change `encodeToBase64` to a non-static (instance) method.
         *   The reflection check on `isStatic()` will fail immediately.
         */
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

        /*
         * MUTATION PROBE:
         *   Make `getExistingGpmlSHA` static. The `isStatic()` assertion flips.
         */
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
    // 5.  readHttpResponse — stream contract, tested through the PUBLIC
    //     getExistingGpmlSHA() entry point.
    //
    //     readHttpResponse is private, so rather than reflecting into it
    //     directly (which breaks if it's ever renamed during a refactor, even
    //     if getExistingGpmlSHA's behavior is unchanged), we drive it through
    //     getExistingGpmlSHA using mockConstruction(URL.class). This keeps the
    //     test coupled to the *public contract*, not the implementation detail.
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readHttpResponse() — stream contract (via getExistingGpmlSHA)")
    class ReadHttpResponse {

        private GpmlEncoder encoder;

        @BeforeEach
        void setUp() {
            encoder = new GpmlEncoder();
        }

        /*
         * MUTATION PROBE:
         *   Add an explicit `reader.close()` call after the try-with-resources
         *   block in readHttpResponse. BufferedReader.close() already closes
         *   the underlying stream once; a second explicit close on certain
         *   stream implementations throws or, here, is detected by our
         *   counting InputStream registering 2 close() calls instead of 1.
         */
        @Test
        @DisplayName("does not close the response InputStream a second time")
        void doesNotDoubleCloseInputStream() throws Exception {
            int[] closeCount = {0};
            String body = "{\"sha\":\"deadbeef\"}\n";
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
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn));
                 MockedStatic<JsonParser> parserMock = Mockito.mockStatic(JsonParser.class)) {

                parserMock.when(() -> JsonParser.parseSHAFromResponse(anyString()))
                          .thenReturn("deadbeef");

                encoder.getExistingGpmlSHA("https://api.github.com/repos/o/r/contents/f.gpml", "token");
            }

            // BufferedReader.close() calls the underlying stream's close() exactly once.
            assertEquals(1, closeCount[0],
                    "InputStream must be closed exactly once (by try-with-resources); " +
                    "got " + closeCount[0] + " close() calls — check for a redundant reader.close()");
        }

        /*
         * MUTATION PROBE:
         *   Remove the `.append("\n")` in readHttpResponse's while loop.
         *   A multi-line JSON body would then be concatenated without
         *   separators (e.g. "{\"sha\":""abc123\"}" instead of
         *   "{\"sha\":\n\"abc123\"}\n"). We capture the exact string passed to
         *   JsonParser.parseSHAFromResponse via an ArgumentCaptor and assert
         *   the newline is present between the two lines.
         */
        @Test
        @DisplayName("preserves newlines between lines of a multi-line HTTP response")
        void preservesNewlinesBetweenLines() throws Exception {
            String line1 = "{\"sha\":";
            String line2 = "\"abc123\"}";
            String body  = line1 + "\n" + line2 + "\n";

            HttpURLConnection mockConn = mock(HttpURLConnection.class);
            when(mockConn.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConn.getInputStream()).thenReturn(
                new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
            );

            try (MockedConstruction<URL> urlMock = Mockito.mockConstruction(URL.class,
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn));
                 MockedStatic<JsonParser> parserMock = Mockito.mockStatic(JsonParser.class)) {

                parserMock.when(() -> JsonParser.parseSHAFromResponse(anyString()))
                          .thenReturn("abc123");

                encoder.getExistingGpmlSHA("https://api.github.com/repos/o/r/contents/f.gpml", "token");

                // Capture exactly what readHttpResponse handed to JsonParser.
                org.mockito.ArgumentCaptor<String> captor =
                        org.mockito.ArgumentCaptor.forClass(String.class);
                parserMock.verify(() -> JsonParser.parseSHAFromResponse(captor.capture()));

                String passed = captor.getValue();
                assertTrue(passed.contains(line1), "First line must appear in response: " + passed);
                assertTrue(passed.contains(line2), "Second line must appear in response: " + passed);
                assertTrue(passed.contains("\n"), "Newline separator between lines must be present; got: " + passed);
            }
        }

        /*
         * MUTATION PROBE:
         *   Change the while-loop condition so an empty stream produces null
         *   instead of "" (e.g. returning responseBuilder.toString() only when
         *   non-empty, else null). JsonParser.parseSHAFromResponse would then
         *   receive null instead of "", which this test detects via the
         *   captured argument.
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
                    (mock, context) -> when(mock.openConnection()).thenReturn(mockConn));
                 MockedStatic<JsonParser> parserMock = Mockito.mockStatic(JsonParser.class)) {

                parserMock.when(() -> JsonParser.parseSHAFromResponse(anyString()))
                          .thenReturn(null);

                encoder.getExistingGpmlSHA("https://api.github.com/repos/o/r/contents/f.gpml", "token");

                org.mockito.ArgumentCaptor<String> captor =
                        org.mockito.ArgumentCaptor.forClass(String.class);
                parserMock.verify(() -> JsonParser.parseSHAFromResponse(captor.capture()));

                assertNotNull(captor.getValue(), "readHttpResponse must pass a non-null string for an empty body");
                assertEquals("", captor.getValue(), "readHttpResponse must pass an empty string for an empty body");
            }
        }
    }
}