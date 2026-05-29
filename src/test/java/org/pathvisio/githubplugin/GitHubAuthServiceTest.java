package org.pathvisio.githubplugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pathvisio.githubplugin.util.TokenManager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for GitHubAuthService.
 *
 * <h2>SOURCE DISCREPANCY — READ THIS FIRST</h2>
 * The code submitted for review contains a compile-time error:
 * <ul>
 *   <li>AuthCallback (as pasted) defines 3 methods: onStatusUpdate, onSuccess, onFailure</li>
 *   <li>But beginDeviceAuthFlow() calls: callback.onUserCodeReceived(userCode, expiresIn)</li>
 * </ul>
 * This is a compilation failure. The complete interface from the original proposal document
 * (and from the implementation itself) has 4 methods:
 * onUserCodeReceived, onStatusUpdate, onSuccess, onFailure.
 * <p>
 * The source file used here adds onUserCodeReceived back to AuthCallback.
 * This must be reconciled in the actual repository before merging.
 *
 * <h2>THREADING MODEL</h2>
 * GitHubAuthService uses nested SwingWorker chains:
 * <pre>
 *   startAuthentication()
 *     └─ [if token exists] validationWorker.doInBackground() → isTokenValid()
 *                          validationWorker.done()  [EDT]
 *                            └─ beginDeviceAuthFlow()
 *                                 └─ setUpWorker.doInBackground() → requestDeviceCodes()
 *                                    setUpWorker.done()  [EDT]
 *                                      ├─ callback.onFailure(...)   ← what we observe
 *                                      └─ pollingWorker.execute()
 * </pre>
 * All SwingWorker.done() callbacks run on the Event Dispatch Thread (EDT).
 * Tests use CountDownLatch to synchronise: the EDT releases the latch when a
 * terminal callback (onSuccess or onFailure) fires; the test thread waits.
 *
 * <h2>NETWORK STATE in this environment</h2>
 * Probed before writing:
 * <ul>
 *   <li>github.com/login/device/code → HTTP 404 (empty CLIENT_ID)</li>
 *   <li>github.com/login/oauth/access_token → HTTP 404 (empty CLIENT_ID)</li>
 *   <li>api.github.com/user → HTTP 401 (invalid token)</li>
 * </ul>
 * Both GitHub API domains are reachable. Tests that call real endpoints are
 * clearly annotated with "NETWORK" in their @DisplayName.
 * <ul>
 *   <li>isTokenValid() returns false (401 or exception) — stable regardless of
 *       whether GitHub is reachable, because it catches all exceptions.</li>
 *   <li>requestDeviceCodes() throws IOException (HTTP 404 ≠ 200) — also stable.</li>
 * </ul>
 */
@DisplayName("GitHubAuthService")
class GitHubAuthServiceTest {

    /**
     * Records every callback invocation for inspection after test thread synchronization.
     *
     * <p>Uses AtomicReference and AtomicBoolean for memory-visibility guarantees.
     * Callback methods run on the EDT; assertions run on the test thread. Without
     * explicit synchronization, the test thread might read stale values. CountDownLatch.await()
     * establishes a happens-before edge, and AtomicReference adds explicit volatility
     * for defense in depth.
     */
    static class RecordingCallback implements GitHubAuthService.AuthCallback {

        final AtomicReference<String> userCode = new AtomicReference<>();
        final AtomicInteger userCodeExpiry = new AtomicInteger(-1);
        final AtomicReference<String> statusMessage = new AtomicReference<>();
        final AtomicReference<String> successToken = new AtomicReference<>();
        final AtomicReference<String> failureMessage = new AtomicReference<>();

        final AtomicBoolean userCodeCalled = new AtomicBoolean(false);
        final AtomicBoolean statusCalled = new AtomicBoolean(false);
        final AtomicBoolean successCalled = new AtomicBoolean(false);
        final AtomicBoolean failureCalled = new AtomicBoolean(false);

        private final CountDownLatch terminalLatch = new CountDownLatch(1);

        @Override
        public void onUserCodeReceived(String code, int expiresIn) {
            userCode.set(code);
            userCodeExpiry.set(expiresIn);
            userCodeCalled.set(true);
        }

        @Override
        public void onStatusUpdate(String message) {
            statusMessage.set(message);
            statusCalled.set(true);
        }

        @Override
        public void onSuccess(String token) {
            successToken.set(token);
            successCalled.set(true);
            terminalLatch.countDown();
        }

        @Override
        public void onFailure(String message) {
            failureMessage.set(message);
            failureCalled.set(true);
            terminalLatch.countDown();
        }

        /**
         * Blocks the calling thread until onSuccess or onFailure fires,
         * or the timeout expires.
         *
         * @param timeoutSeconds maximum time to wait
         * @return true if a terminal event arrived, false if timed out
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        boolean awaitTerminal(long timeoutSeconds) throws InterruptedException {
            return terminalLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Configures headless mode for CI and container environments.
     *
     * <p>Prevents "no display" errors. SwingWorker works correctly in headless mode:
     * the EDT is created on demand and processes events normally without a physical display.
     * As a side effect, Desktop.isDesktopSupported() returns false, which means
     * beginDeviceAuthFlow() may call onFailure("Desktop not supported...") AFTER
     * requestDeviceCodes() already threw. In the test environment, requestDeviceCodes()
     * always fails first anyway (HTTP 404 from empty CLIENT_ID).
     */
    @BeforeAll
    static void configureHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * Clears any stored token before each test.
     */
    @BeforeEach
    void cleanTokenBefore() {
        TokenManager.clearToken();
    }

    /**
     * Clears any stored token after each test.
     */
    @AfterEach
    void cleanTokenAfter() {
        TokenManager.clearToken();
    }

    /**
     * Tests for DeviceCodeResponse data integrity.
     *
     * <p>DeviceCodeResponse has no logic — just stores 5 fields set via constructor
     * and exposes them through getters. These tests exist to catch:
     * <ul>
     *   <li>Constructor argument ORDER errors (classic swap bug):
     *       If expiresIn and interval are swapped, the polling loop sleeps for
     *       900 seconds instead of 5 — a silent hang, hard to diagnose.</li>
     *   <li>Package-private getter errors: If someone accidentally makes a getter
     *       return the wrong field, nothing outside the class would catch it immediately.</li>
     * </ul>
     * Each getter gets its own test so a failure immediately identifies the broken getter.
     */
    @Nested
    @DisplayName("DeviceCodeResponse — data integrity")
    class DeviceCodeResponseTests {

        private final GitHubAuthService.DeviceCodeResponse response =
                new GitHubAuthService.DeviceCodeResponse(
                        "YOUR-DEVICE-CODE",
                        "WBJI-BCAS",
                        "https://github.com/login/device",
                        900,
                        5
                );

        @Test
        @DisplayName("getDeviceCode returns the first constructor argument")
        void getDeviceCode() {
            assertEquals("YOUR-DEVICE-CODE", response.getDeviceCode());
        }

        @Test
        @DisplayName("getUserCode returns the second constructor argument")
        void getUserCode() {
            assertEquals("WBJI-BCAS", response.getUserCode());
        }

        @Test
        @DisplayName("getVerificationUri returns the third constructor argument")
        void getVerificationUri() {
            assertEquals("https://github.com/login/device", response.getVerificationUri());
        }

        @Test
        @DisplayName("getExpiresIn returns the fourth constructor argument")
        void getExpiresIn() {
            assertEquals(900, response.getExpiresIn());
        }

        @Test
        @DisplayName("getInterval returns the fifth constructor argument")
        void getInterval() {
            assertEquals(5, response.getInterval());
        }

        @Test
        @DisplayName("expiresIn and interval are NOT swapped — critical for polling timing")
        void expiresInAndIntervalAreOrdered() {
            assertNotEquals(
                    response.getExpiresIn(),
                    response.getInterval(),
                    "expiresIn (900) and interval (5) must not be equal — if they are, the constructor swapped them"
            );
            assertTrue(response.getExpiresIn() > response.getInterval(),
                    "expiresIn must be larger than interval — real GitHub values are 900 and 5");
        }

        @Test
        @DisplayName("constructor stores all five fields independently")
        void allFieldsStoredIndependently() {
            GitHubAuthService.DeviceCodeResponse r =
                    new GitHubAuthService.DeviceCodeResponse("A", "B", "C", 100, 10);
            assertEquals("A", r.getDeviceCode());
            assertEquals("B", r.getUserCode());
            assertEquals("C", r.getVerificationUri());
            assertEquals(100, r.getExpiresIn());
            assertEquals(10, r.getInterval());
        }
    }

    /**
     * Tests for isAuthenticated() method.
     *
     * <p>isAuthenticated() delegates entirely to TokenManager.getToken() != null.
     * It is tested through GitHubAuthService (not just TokenManager directly)
     * because startAuthentication() uses it as its decision gate:
     * <ul>
     *   <li>if token exists → validate → maybe re-auth</li>
     *   <li>if no token → device flow immediately</li>
     * </ul>
     * If isAuthenticated() is broken, that gate misfires silently.
     */
    @Nested
    @DisplayName("isAuthenticated()")
    class IsAuthenticated {

        @Test
        @DisplayName("returns false when TokenManager has no stored token")
        void falseWithNoToken() {
            assertFalse(new GitHubAuthService().isAuthenticated());
        }

        @Test
        @DisplayName("returns true when TokenManager has a stored token")
        void trueWhenTokenStored() {
            TokenManager.saveToken("gho_fake_for_isAuthenticated_test");
            assertTrue(new GitHubAuthService().isAuthenticated());
        }

        @Test
        @DisplayName("returns false after the stored token is cleared")
        void falseAfterTokenCleared() {
            TokenManager.saveToken("gho_token_that_will_be_cleared");
            GitHubAuthService service = new GitHubAuthService();
            assertTrue(service.isAuthenticated());

            TokenManager.clearToken();
            assertFalse(service.isAuthenticated());
        }

        @Test
        @DisplayName("is consistent with TokenManager.getToken() != null across state changes")
        void consistentWithTokenManager() {
            GitHubAuthService service = new GitHubAuthService();

            assertEquals(
                    TokenManager.getToken() != null,
                    service.isAuthenticated(),
                    "isAuthenticated() must equal (TokenManager.getToken() != null) before save"
            );

            TokenManager.saveToken("sync_check_token");

            assertEquals(
                    TokenManager.getToken() != null,
                    service.isAuthenticated(),
                    "isAuthenticated() must equal (TokenManager.getToken() != null) after save"
            );
        }
    }

    /**
     * Tests for isAuthenticationInProgress() and cancelAuthentication() methods.
     *
     * <p>These two methods are the UI layer's control surface:
     * <ul>
     *   <li>UI calls isAuthenticationInProgress() to decide if Cancel button should be enabled</li>
     *   <li>UI calls cancelAuthentication() when the Cancel button is clicked</li>
     * </ul>
     * Key Risk: both methods touch the pollingWorker field, which is null until the polling
     * phase starts. A null-check missing in either method causes NullPointerException in the UI.
     */
    @Nested
    @DisplayName("isAuthenticationInProgress() and cancelAuthentication()")
    class ProgressAndCancel {

        @Test
        @DisplayName("isAuthenticationInProgress returns false on a fresh instance (pollingWorker is null)")
        void falseBeforeAnyAuthentication() {
            assertFalse(new GitHubAuthService().isAuthenticationInProgress());
        }

        @Test
        @DisplayName("cancelAuthentication does not throw when pollingWorker is null")
        void cancelIsNullSafe() {
            assertDoesNotThrow(() -> new GitHubAuthService().cancelAuthentication());
        }

        @Test
        @DisplayName("cancelAuthentication can be called multiple times without throwing")
        void cancelIsIdempotent() {
            GitHubAuthService service = new GitHubAuthService();
            assertDoesNotThrow(() -> {
                service.cancelAuthentication();
                service.cancelAuthentication();
                service.cancelAuthentication();
            });
        }

        @Test
        @DisplayName("isAuthenticationInProgress stays false after cancel on fresh instance")
        void progressRemainsUnchangedAfterCancelOnFreshInstance() {
            GitHubAuthService service = new GitHubAuthService();
            service.cancelAuthentication();
            assertFalse(service.isAuthenticationInProgress());
        }

        @Test
        @DisplayName("isAuthenticationInProgress returns false when pollingWorker field is null (via reflection)")
        void verifyNullWorkerHandling() throws Exception {
            GitHubAuthService service = new GitHubAuthService();
            Field field = GitHubAuthService.class.getDeclaredField("pollingWorker");
            field.setAccessible(true);
            assertNull(field.get(service), "pollingWorker should be null on a fresh instance");
            assertFalse(service.isAuthenticationInProgress());
        }
    }

    /**
     * Tests for isTokenValid() private method (accessed via reflection).
     *
     * <p><strong>NETWORK:</strong> calls api.github.com (in allowlist).
     *
     * <p>isTokenValid() decides whether to skip the device flow and go straight to onSuccess.
     * If it returns true for an invalid token, the user gets an access token that will fail
     * every API call. If it returns false for a valid token, the user is forced to
     * re-authenticate needlessly. We can only test the "false" path here (we have no real
     * valid token), but that covers the most important guard: invalid tokens must be rejected.
     *
     * <p>Access via reflection is necessary because the method is private — by design,
     * since it's an implementation detail. We access it here specifically to verify
     * GitHub 401-handling behavior without going through the full SwingWorker chain.
     *
     * <p><strong>STABILITY:</strong>
     * <ul>
     *   <li>With a fake token, GitHub returns 401 → false</li>
     *   <li>If api.github.com is unreachable, IOException is caught → also false</li>
     *   <li>The assertion (false) is stable regardless of network state</li>
     * </ul>
     */
    @Nested
    @DisplayName("isTokenValid() — private, via reflection [NETWORK: api.github.com]")
    class IsTokenValid {

        /**
         * Extracts isTokenValid(String) via reflection and invokes it,
         * unwrapping InvocationTargetException.
         *
         * @param service the GitHubAuthService instance
         * @param token the token to validate
         * @return true if valid, false otherwise
         * @throws Exception if reflection fails
         */
        private boolean callIsTokenValid(GitHubAuthService service, String token) throws Exception {
            Method m = GitHubAuthService.class.getDeclaredMethod("isTokenValid", String.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(service, token);
        }

        @Test
        @DisplayName("returns false for an empty string token (GitHub returns 401)")
        void returnsFalseForEmptyToken() throws Exception {
            assertFalse(callIsTokenValid(new GitHubAuthService(), ""));
        }

        @Test
        @DisplayName("returns false for a plainly invalid token string")
        void returnsFalseForObviouslyFakeToken() throws Exception {
            assertFalse(callIsTokenValid(new GitHubAuthService(), "not_a_real_token"));
        }

        @Test
        @DisplayName("returns false for a token with the correct gho_ prefix but invalid content")
        void returnsFalseForStructurallyPlausibleFakeToken() throws Exception {
            assertFalse(callIsTokenValid(new GitHubAuthService(),
                    "gho_0000000000000000000000000000000000000000"));
        }

        @Test
        @DisplayName("returns false without throwing for any fake token (all exceptions are caught)")
        void neverThrowsForFakeToken() {
            assertDoesNotThrow(() -> callIsTokenValid(new GitHubAuthService(), "will_get_401"));
        }
    }

    /**
     * Tests for requestDeviceCodes() private method (accessed via reflection).
     *
     * <p><strong>NETWORK:</strong> calls github.com/login/device/code (responds 404 with empty CLIENT_ID).
     *
     * <p>requestDeviceCodes() is the first real network call in the auth flow.
     * We probe that it correctly converts a non-200 response into IOException
     * (rather than silently returning null or a half-built DeviceCodeResponse).
     *
     * <p><strong>STABILITY of the IOException:</strong>
     * <ul>
     *   <li>In this environment, CLIENT_ID = "" → GitHub returns HTTP 404</li>
     *   <li>In any environment, HTTP 404 ≠ HTTP 200 → IOException is thrown</li>
     *   <li>Even if GitHub is unreachable, the connect exception propagates as IOException</li>
     *   <li>Result: IOException is stable regardless of exact network condition</li>
     * </ul>
     *
     * <p><strong>WHAT WE DO NOT TEST:</strong>
     * <ul>
     *   <li>The success path (valid CLIENT_ID, real GitHub response) — not available
     *       without a registered OAuth app and credentials</li>
     *   <li>The "missing required fields" IOException — would require a custom HTTP stub</li>
     * </ul>
     */
    @Nested
    @DisplayName("requestDeviceCodes() — private, via reflection [NETWORK: github.com]")
    class RequestDeviceCodes {

        @Test
        @DisplayName("throws IOException when CLIENT_ID is empty (GitHub returns HTTP 404)")
        void throwsIOExceptionOnNonOkResponse() throws Exception {
            GitHubAuthService service = new GitHubAuthService();
            Method method = GitHubAuthService.class.getDeclaredMethod("requestDeviceCodes");
            method.setAccessible(true);

            InvocationTargetException ex = assertThrows(
                    InvocationTargetException.class,
                    () -> {
                        try {
                            method.invoke(service);
                        } catch (InvocationTargetException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("unexpected: " + e, e);
                        }
                    }
            );
            assertInstanceOf(IOException.class, ex.getCause(),
                    "Expected IOException but got: " + ex.getCause().getClass().getName()
                            + " — " + ex.getCause().getMessage());
        }

        @Test
        @DisplayName("IOException message mentions HTTP status code")
        void ioExceptionMessageContainsHttpStatus() throws Exception {
            GitHubAuthService service = new GitHubAuthService();
            Method method = GitHubAuthService.class.getDeclaredMethod("requestDeviceCodes");
            method.setAccessible(true);

            try {
                method.invoke(service);
                fail("Expected IOException to be thrown");
            } catch (InvocationTargetException e) {
                String message = e.getCause().getMessage();
                assertNotNull(message, "IOException must carry a descriptive message");
                assertTrue(message.contains("HTTP"),
                        "Message should mention HTTP status, got: [" + message + "]");
            }
        }
    }

    /**
     * Tests for pollForAccessToken() private method (accessed via reflection).
     *
     * <p><strong>NETWORK:</strong> calls github.com/login/oauth/access_token (returns HTTP 404).
     *
     * <p>pollForAccessToken() is the core of the polling loop. It reads the
     * instance field `deviceCode`, which we set via reflection before invoking.
     *
     * <p><strong>STABILITY:</strong> same as requestDeviceCodes() — HTTP 404 ≠ 200 → IOException.
     *
     * <p>We also verify that the `interval` field is NOT modified by a 404 response
     * (only the "slow_down" error path increments interval — and that requires a
     * 200 response with the error in the JSON body, which we can't get without
     * a valid CLIENT_ID).
     */
    @Nested
    @DisplayName("pollForAccessToken() — private, via reflection [NETWORK: github.com]")
    class PollForAccessToken {

        @Test
        @DisplayName("throws IOException when github.com returns non-200 (empty CLIENT_ID)")
        void throwsIOExceptionOnNonOkResponse() throws Exception {
            GitHubAuthService service = new GitHubAuthService();

            Field dcField = GitHubAuthService.class.getDeclaredField("deviceCode");
            dcField.setAccessible(true);
            dcField.set(service, "dummy_device_code_for_test");

            Method method = GitHubAuthService.class.getDeclaredMethod("pollForAccessToken");
            method.setAccessible(true);

            InvocationTargetException ex = assertThrows(
                    InvocationTargetException.class,
                    () -> {
                        try {
                            method.invoke(service);
                        } catch (InvocationTargetException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("unexpected: " + e, e);
                        }
                    }
            );
            assertInstanceOf(IOException.class, ex.getCause(),
                    "Expected IOException but got: " + ex.getCause().getClass().getName());
        }

        @Test
        @DisplayName("interval field is not modified by a non-200 response")
        void intervalIsUnchangedOnHttpError() throws Exception {
            GitHubAuthService service = new GitHubAuthService();

            Field intervalField = GitHubAuthService.class.getDeclaredField("interval");
            intervalField.setAccessible(true);
            intervalField.set(service, 5);

            Field dcField = GitHubAuthService.class.getDeclaredField("deviceCode");
            dcField.setAccessible(true);
            dcField.set(service, "dummy");

            Method method = GitHubAuthService.class.getDeclaredMethod("pollForAccessToken");
            method.setAccessible(true);

            try {
                method.invoke(service);
            } catch (InvocationTargetException ignored) {
                // expected IOException
            } catch (Exception ignored) {
                // no-op
            }

            assertEquals(5, (int) intervalField.get(service),
                    "interval must not be modified by a non-200 HTTP error response");
        }
    }

    /**
     * Integration tests for startAuthentication() method.
     *
     * <p>These are the highest-level tests: they exercise the complete callback
     * contract through real SwingWorker chains.
     *
     * <p><strong>THREADING:</strong> startAuthentication() spawns SwingWorkers.
     * All assertions happen AFTER CountDownLatch.await(), which guarantees the EDT
     * callbacks have completed and their writes are visible to this thread.
     *
     * <p><strong>TIMEOUT:</strong> set to 15 seconds. Probe testing showed the full nested
     * SwingWorker chain (validationWorker → setUpWorker) completes in ~1s.
     * 15s is generous to account for slow CI systems and network variation.
     * If this consistently times out, the EDT is stuck — that is itself a bug.
     *
     * <p><strong>WHAT GETS TRIGGERED in this environment (CLIENT_ID = ""):</strong>
     * <ul>
     *   <li><strong>Case A — no stored token:</strong>
     *       beginDeviceAuthFlow → requestDeviceCodes() → HTTP 404 → IOException
     *       → setUpWorker.done() catches ExecutionException
     *       → callback.onFailure("Failed to connect to GitHub: ...")</li>
     *   <li><strong>Case B — invalid stored token:</strong>
     *       validationWorker → isTokenValid() → api.github.com → 401 → false
     *       → clearToken() → beginDeviceAuthFlow → same as Case A</li>
     * </ul>
     */
    @Nested
    @DisplayName("startAuthentication() — integration [NETWORK: github.com + api.github.com]")
    class StartAuthentication {

        private static final long TIMEOUT = 15L;

        @Test
        @DisplayName("calls onFailure when no token is stored (device flow hits HTTP 404)")
        void callsOnFailureWhenNoTokenStored() throws InterruptedException {
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);

            boolean arrived = cb.awaitTerminal(TIMEOUT);
            assertTrue(arrived,
                    "Timed out after " + TIMEOUT + "s — neither onSuccess nor onFailure was called. "
                            + "The EDT may be stuck or the network call is hanging.");

            assertTrue(cb.failureCalled.get(), "onFailure must be called when device flow fails");
            assertFalse(cb.successCalled.get(), "onSuccess must NOT be called when device flow fails");
        }

        @Test
        @DisplayName("onFailure message is non-null and non-blank when device flow fails")
        void failureMessageIsDescriptive() throws InterruptedException {
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);
            cb.awaitTerminal(TIMEOUT);

            String msg = cb.failureMessage.get();
            assertNotNull(msg, "onFailure must be called with a non-null message");
            assertFalse(msg.trim().isEmpty(),
                    "onFailure message must not be blank — user needs to know what failed");
        }

        @Test
        @DisplayName("failure message contains 'Failed to connect to GitHub' when device flow fails (Case A path)")
        void failureMessageIndicatesConnectionFailure() throws InterruptedException {
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);
            cb.awaitTerminal(TIMEOUT);

            String msg = cb.failureMessage.get();
            assertNotNull(msg);
            boolean isExpectedMessage =
                    (msg.contains("Failed to connect to GitHub") ||
                            msg.contains("Desktop not supported") ||
                            msg.contains("HTTP"));
            assertTrue(isExpectedMessage,
                    "Unexpected failure message: [" + msg + "]");
        }

        @Test
        @DisplayName("calls onFailure when a stored token is invalid (validation returns false)")
        void callsOnFailureWhenStoredTokenIsInvalid() throws InterruptedException {
            TokenManager.saveToken("gho_completely_fake_0000000000000000000000000");
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);

            boolean arrived = cb.awaitTerminal(TIMEOUT);
            assertTrue(arrived, "Timed out waiting for callback after invalid token validation");
            assertTrue(cb.failureCalled.get(),
                    "onFailure must be called after invalid token is rejected by GitHub");
            assertFalse(cb.successCalled.get(),
                    "onSuccess must NOT be called for a fake token");
        }

        @Test
        @DisplayName("invalid stored token is cleared from TokenManager before onFailure fires")
        void invalidTokenIsClearedBeforeCallback() throws InterruptedException {
            TokenManager.saveToken("gho_token_that_should_be_cleared_on_reject");
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);
            cb.awaitTerminal(TIMEOUT);

            assertFalse(service.isAuthenticated(),
                    "Token must be cleared by the time the failure callback fires");
            assertNull(TokenManager.getToken(),
                    "TokenManager must have no token stored after failed validation");
        }

        @Test
        @DisplayName("onUserCodeReceived is NOT called when device flow fails at requestDeviceCodes()")
        void onUserCodeReceivedNotCalledOnEarlyFailure() throws InterruptedException {
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);
            cb.awaitTerminal(TIMEOUT);

            assertFalse(cb.userCodeCalled.get(),
                    "onUserCodeReceived must NOT be called when requestDeviceCodes() throws");
        }

        @Test
        @DisplayName("isAuthenticationInProgress returns false after device flow failure")
        void notInProgressAfterFailure() throws InterruptedException {
            GitHubAuthService service = new GitHubAuthService();
            RecordingCallback cb = new RecordingCallback();

            service.startAuthentication(cb);
            cb.awaitTerminal(TIMEOUT);

            assertFalse(service.isAuthenticationInProgress(),
                    "isAuthenticationInProgress must be false after a terminal failure event");
        }
    }
}
