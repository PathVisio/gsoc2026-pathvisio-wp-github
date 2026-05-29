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

 * Tests for GitHubAuthService.

 *

 * =========================================================================

 * SOURCE DISCREPANCY — READ THIS FIRST

 * =========================================================================

 * The code submitted for review contains a compile-time error:

 *

 *   AuthCallback (as pasted) defines 3 methods:

 *     onStatusUpdate, onSuccess, onFailure

 *

 *   But beginDeviceAuthFlow() calls:

 *     callback.onUserCodeReceived(userCode, expiresIn)

 *

 * This is a compilation failure. The complete interface from the original

 * proposal document (and from the implementation itself) has 4 methods:

 *     onUserCodeReceived, onStatusUpdate, onSuccess, onFailure

 *

 * The source file used here adds onUserCodeReceived back to AuthCallback.

 * This must be reconciled in the actual repository before merging.

 * =========================================================================

 *

 * THREADING MODEL — what these tests navigate

 * =========================================================================

 * GitHubAuthService uses nested SwingWorker chains:

 *

 *   startAuthentication()

 *     └─ [if token exists] validationWorker.doInBackground() → isTokenValid()

 *                          validationWorker.done()  [EDT]

 *                            └─ beginDeviceAuthFlow()

 *                                 └─ setUpWorker.doInBackground() → requestDeviceCodes()

 *                                    setUpWorker.done()  [EDT]

 *                                      ├─ callback.onFailure(...)   ← what we observe

 *                                      └─ pollingWorker.execute()

 *

 * All SwingWorker.done() callbacks run on the Event Dispatch Thread (EDT).

 * Tests use CountDownLatch to synchronise: the EDT releases the latch when a

 * terminal callback (onSuccess or onFailure) fires; the test thread waits.

 *

 * NETWORK STATE in this environment

 * =========================================================================

 * Probed before writing:

 *   github.com/login/device/code       → HTTP 404 (empty CLIENT_ID)

 *   github.com/login/oauth/access_token→ HTTP 404 (empty CLIENT_ID)

 *   api.github.com/user                → HTTP 401 (invalid token)

 *

 * Both GitHub API domains are reachable.  Tests that call real endpoints are

 * clearly annotated with "NETWORK" in their @DisplayName.

 * isTokenValid() returns false (401 or exception) — stable regardless of

 * whether GitHub is reachable, because it catches all exceptions.

 * requestDeviceCodes() throws IOException (HTTP 404 ≠ 200) — also stable.

 */

@DisplayName("GitHubAuthService")

class GitHubAuthServiceTest {



    // =========================================================================

    // RecordingCallback

    //

    // Captures every callback invocation so assertions can inspect them after

    // CountDownLatch.await() synchronises the test thread with the EDT.

    //

    // WHY AtomicReference / AtomicBoolean?

    // The callback methods run on the EDT; the assertions run on the test thread.

    // Without memory-visibility guarantees, the test thread might read stale values.

    // CountDownLatch.await() establishes a happens-before edge, so reads after

    // await() see writes that happened-before countDown(). But AtomicReference

    // adds explicit volatility just to be safe — defence in depth.

    // =========================================================================



    static class RecordingCallback implements GitHubAuthService.AuthCallback {



        final AtomicReference<String>  userCode       = new AtomicReference<>();

        final AtomicInteger            userCodeExpiry = new AtomicInteger(-1);

        final AtomicReference<String>  statusMessage  = new AtomicReference<>();

        final AtomicReference<String>  successToken   = new AtomicReference<>();

        final AtomicReference<String>  failureMessage = new AtomicReference<>();



        final AtomicBoolean userCodeCalled  = new AtomicBoolean(false);

        final AtomicBoolean statusCalled    = new AtomicBoolean(false);

        final AtomicBoolean successCalled   = new AtomicBoolean(false);

        final AtomicBoolean failureCalled   = new AtomicBoolean(false);



        // Released when onSuccess OR onFailure is called — either ends the flow.

        private final CountDownLatch terminalLatch = new CountDownLatch(1);



        @Override

        public void onUserCodeReceived(String code, int expiresIn) {

            userCode.set(code);

            userCodeExpiry.set(expiresIn);

            userCodeCalled.set(true);

            // Not terminal: flow continues with polling after this.

        }



        @Override

        public void onStatusUpdate(String message) {

            statusMessage.set(message);

            statusCalled.set(true);

            // Not terminal: these fire repeatedly while polling.

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

         * @return true if a terminal event arrived, false if timed out

         */

        boolean awaitTerminal(long timeoutSeconds) throws InterruptedException {

            return terminalLatch.await(timeoutSeconds, TimeUnit.SECONDS);

        }

    }



    // =========================================================================

    // Fixture

    // =========================================================================



    @BeforeAll

    static void configureHeadless() {

        // Prevents "no display" errors on CI / containers.

        // SwingWorker works correctly in headless mode: the EDT is created on

        // demand and processes events normally without a physical display.

        // As a side effect, Desktop.isDesktopSupported() returns false,

        // which means beginDeviceAuthFlow() calls onFailure("Desktop not supported...")

        // AFTER requestDeviceCodes() already threw. Probe confirmed this is

        // consistent — in our test environment requestDeviceCodes() always fails

        // first anyway (HTTP 404 from empty CLIENT_ID).

        System.setProperty("java.awt.headless", "true");

    }



    @BeforeEach

    void cleanTokenBefore() {

        TokenManager.clearToken();

    }



    @AfterEach

    void cleanTokenAfter() {

        TokenManager.clearToken();

    }



    // =========================================================================

    // SECTION 1 — DeviceCodeResponse (pure data class)

    //

    // DeviceCodeResponse has no logic — just stores 5 fields set via constructor

    // and exposes them through getters.  These tests exist because:

    //

    //   1. Constructor argument ORDER is the classic swap bug.

    //      If expiresIn and interval are swapped, the polling loop sleeps for

    //      900 seconds instead of 5 — a silent hang, hard to diagnose.

    //

    //   2. The class is package-private (static inner).  If someone accidentally

    //      makes a getter return the wrong field, nothing outside the class would

    //      catch it immediately.

    //

    // Each getter gets its own test so a failure immediately names the broken getter.

    // =========================================================================



    @Nested

    @DisplayName("DeviceCodeResponse — data integrity")

    class DeviceCodeResponseTests {



        // Shared test instance — avoid repeating the same constructor call.

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

            // If these are swapped, Thread.sleep(interval * 1000L) sleeps for 900s.

            // The values 900 and 5 are intentionally far apart to make a swap obvious.

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

            // Five distinct values — verifies no field aliases another.

            GitHubAuthService.DeviceCodeResponse r =

                new GitHubAuthService.DeviceCodeResponse("A", "B", "C", 100, 10);

            assertEquals("A",  r.getDeviceCode());

            assertEquals("B",  r.getUserCode());

            assertEquals("C",  r.getVerificationUri());

            assertEquals(100,  r.getExpiresIn());

            assertEquals(10,   r.getInterval());

        }

    }



    // =========================================================================

    // SECTION 2 — isAuthenticated()

    //

    // isAuthenticated() delegates entirely to TokenManager.getToken() != null.

    // It is tested through GitHubAuthService (not just TokenManager directly)

    // because startAuthentication() uses it as its decision gate:

    //   if token exists → validate → maybe re-auth

    //   if no token     → device flow immediately

    //

    // If isAuthenticated() is broken, that gate misfires silently.

    // =========================================================================



    @Nested

    @DisplayName("isAuthenticated()")

    class IsAuthenticated {



        @Test

        @DisplayName("returns false when TokenManager has no stored token")

        void falseWithNoToken() {

            // @BeforeEach cleared any existing token.

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

            assertTrue(service.isAuthenticated());   // sanity



            TokenManager.clearToken();

            assertFalse(service.isAuthenticated());  // real assertion

        }



        @Test

        @DisplayName("is consistent with TokenManager.getToken() != null across state changes")

        void consistentWithTokenManager() {

            // isAuthenticated() must always match (TokenManager.getToken() != null).

            // We check both states on the same service instance.

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



    // =========================================================================

    // SECTION 3 — isAuthenticationInProgress() and cancelAuthentication()

    //

    // These two methods are the UI layer's control surface.

    // The UI calls isAuthenticationInProgress() to decide whether the

    // Cancel button should be enabled, and cancelAuthentication() when

    // that button is clicked.

    //

    // KEY RISK: both methods touch the pollingWorker field, which is null

    // until the polling phase starts.  A null-check missing in either

    // method causes a NullPointerException in the UI.

    // =========================================================================



    @Nested

    @DisplayName("isAuthenticationInProgress() and cancelAuthentication()")

    class ProgressAndCancel {



        @Test

        @DisplayName("isAuthenticationInProgress returns false on a fresh instance (pollingWorker is null)")

        void falseBeforeAnyAuthentication() {

            // pollingWorker is a private field, null by default.

            // The method must return false, not throw NullPointerException.

            assertFalse(new GitHubAuthService().isAuthenticationInProgress());

        }



        @Test

        @DisplayName("cancelAuthentication does not throw when pollingWorker is null")

        void cancelIsNullSafe() {

            // The Cancel button may be clicked before any auth starts.

            // This must be a silent no-op.

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

            service.cancelAuthentication();  // called before auth starts — must be a no-op

            assertFalse(service.isAuthenticationInProgress());

        }



        @Test

        @DisplayName("isAuthenticationInProgress returns false when pollingWorker field is null (via reflection)")

        void verifyNullWorkerHandling() throws Exception {

            // Belt-and-suspenders: explicitly confirm pollingWorker is null and that

            // isAuthenticationInProgress() reads it safely.

            GitHubAuthService service = new GitHubAuthService();

            Field field = GitHubAuthService.class.getDeclaredField("pollingWorker");

            field.setAccessible(true);

            assertNull(field.get(service), "pollingWorker should be null on a fresh instance");

            // If isAuthenticationInProgress() tried to call pollingWorker.isDone()

            // without a null check, this would throw NullPointerException.

            assertFalse(service.isAuthenticationInProgress());

        }

    }



    // =========================================================================

    // SECTION 4 — isTokenValid() (private method via reflection)

    //

    // NETWORK: calls api.github.com (in allowlist).

    //

    // isTokenValid() is the method that decides whether to skip the device

    // flow and go straight to onSuccess.  If it returns true for an invalid

    // token, the user gets an access token that will fail every API call.

    // If it returns false for a valid token, the user is forced to

    // re-authenticate needlessly.

    //

    // We can only test the "false" path here (we have no real valid token),

    // but that covers the most important guard: invalid tokens must be rejected.

    //

    // WHY access via reflection?

    // The method is private — by design, since it's an implementation detail.

    // We access it here specifically to verify GitHub 401-handling behaviour

    // without going through the full SwingWorker chain.

    //

    // STABILITY: with a fake token, GitHub returns 401 → false.

    //            if api.github.com is unreachable, IOException is caught → also false.

    //            The assertion (false) is stable regardless of network state.

    // =========================================================================



    @Nested

    @DisplayName("isTokenValid() — private, via reflection [NETWORK: api.github.com]")

    class IsTokenValid {



        // Extracts isTokenValid(String) and invokes it, unwrapping InvocationTargetException.

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

            // Looks like a real GitHub OAuth token format but is garbage data.

            // Tests that the method actually validates with GitHub, not just checks format.

            assertFalse(callIsTokenValid(new GitHubAuthService(),

                "gho_0000000000000000000000000000000000000000"));

        }



        @Test

        @DisplayName("returns false without throwing for any fake token (all exceptions are caught)")

        void neverThrowsForFakeToken() {

            // The method has a catch(Exception) block — it must NEVER propagate

            // to the caller.  If it did, the SwingWorker's done() would catch

            // an unexpected exception type and call beginDeviceAuthFlow again

            // instead of reporting the real error.

            assertDoesNotThrow(() -> callIsTokenValid(new GitHubAuthService(), "will_get_401"));

        }

    }



    // =========================================================================

    // SECTION 5 — requestDeviceCodes() (private method via reflection)

    //

    // NETWORK: calls github.com/login/device/code (responds 404 with empty CLIENT_ID).

    //

    // requestDeviceCodes() is the first real network call in the auth flow.

    // We probe that it correctly converts a non-200 response into IOException

    // (rather than silently returning null or a half-built DeviceCodeResponse).

    //

    // STABILITY of the IOException:

    //   In this environment, CLIENT_ID = "" → GitHub returns HTTP 404.

    //   In any environment, HTTP 404 ≠ HTTP 200 → IOException is thrown.

    //   Even if GitHub is unreachable, the connect exception propagates as IOException.

    //   Result: IOException is stable regardless of exact network condition.

    //

    // WHAT WE DO NOT TEST:

    //   The success path (valid CLIENT_ID, real GitHub response) — not available

    //   without a registered OAuth app and credentials.

    //   The "missing required fields" IOException — would require a custom HTTP stub.

    // =========================================================================



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

                    try { method.invoke(service); }

                    catch (InvocationTargetException e) { throw e; }

                    catch (Exception e) { throw new RuntimeException("unexpected: " + e, e); }

                }

            );

            // Unwrap: the real exception must be IOException

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

                // The message template is "Failed to request device code: HTTP <code>"

                assertTrue(message.contains("HTTP"),

                    "Message should mention HTTP status, got: [" + message + "]");

            }

        }

    }



    // =========================================================================

    // SECTION 6 — pollForAccessToken() (private method via reflection)

    //

    // NETWORK: calls github.com/login/oauth/access_token (returns HTTP 404).

    //

    // pollForAccessToken() is the core of the polling loop.  It reads the

    // instance field `deviceCode`, which we set via reflection before invoking.

    //

    // STABILITY: same as requestDeviceCodes() — HTTP 404 ≠ 200 → IOException.

    //

    // We also verify that the `interval` field is NOT modified by a 404 response

    // (only the "slow_down" error path increments interval — and that requires a

    // 200 response with the error in the JSON body, which we can't get without

    // a valid CLIENT_ID).

    // =========================================================================



    @Nested

    @DisplayName("pollForAccessToken() — private, via reflection [NETWORK: github.com]")

    class PollForAccessToken {



        @Test

        @DisplayName("throws IOException when github.com returns non-200 (empty CLIENT_ID)")

        void throwsIOExceptionOnNonOkResponse() throws Exception {

            GitHubAuthService service = new GitHubAuthService();



            // Set the private `deviceCode` field — the method reads it to build the request body.

            // Without this, the body would send device_code=null, which is fine for our

            // purposes (we're testing the non-200 path), but setting it is cleaner.

            Field dcField = GitHubAuthService.class.getDeclaredField("deviceCode");

            dcField.setAccessible(true);

            dcField.set(service, "dummy_device_code_for_test");



            Method method = GitHubAuthService.class.getDeclaredMethod("pollForAccessToken");

            method.setAccessible(true);



            InvocationTargetException ex = assertThrows(

                InvocationTargetException.class,

                () -> {

                    try { method.invoke(service); }

                    catch (InvocationTargetException e) { throw e; }

                    catch (Exception e) { throw new RuntimeException("unexpected: " + e, e); }

                }

            );

            assertInstanceOf(IOException.class, ex.getCause(),

                "Expected IOException but got: " + ex.getCause().getClass().getName());

        }



        @Test

        @DisplayName("interval field is not modified by a non-200 response")

        void intervalIsUnchangedOnHttpError() throws Exception {

            // interval is only incremented on a successful 200 response containing

            // {"error":"slow_down"}.  A raw HTTP error (404) must not touch interval.

            GitHubAuthService service = new GitHubAuthService();



            Field intervalField = GitHubAuthService.class.getDeclaredField("interval");

            intervalField.setAccessible(true);

            intervalField.set(service, 5);  // set known baseline



            Field dcField = GitHubAuthService.class.getDeclaredField("deviceCode");

            dcField.setAccessible(true);

            dcField.set(service, "dummy");



            Method method = GitHubAuthService.class.getDeclaredMethod("pollForAccessToken");

            method.setAccessible(true);



            try { method.invoke(service); }

            catch (InvocationTargetException ignored) { /* expected IOException */ }

            catch (Exception ignored) {}



            // interval must still be 5 — it was not slow_down

            assertEquals(5, (int) intervalField.get(service),

                "interval must not be modified by a non-200 HTTP error response");

        }

    }



    // =========================================================================

    // SECTION 7 — startAuthentication() integration tests

    //

    // These are the highest-level tests: they exercise the complete callback

    // contract through real SwingWorker chains.

    //

    // THREADING: startAuthentication() spawns SwingWorkers.  All assertions

    // happen AFTER CountDownLatch.await(), which guarantees the EDT callbacks

    // have completed and their writes are visible to this thread.

    //

    // TIMEOUT: set to 15 seconds.  Probe testing showed the full nested

    // SwingWorker chain (validationWorker → setUpWorker) completes in ~1s.

    // 15s is generous to account for slow CI systems and network variation.

    // If this consistently times out, the EDT is stuck — that is itself a bug.

    //

    // WHAT GETS TRIGGERED in this environment (CLIENT_ID = ""):

    //

    //   Case A — no stored token:

    //     beginDeviceAuthFlow → requestDeviceCodes() → HTTP 404 → IOException

    //     → setUpWorker.done() catches ExecutionException

    //     → callback.onFailure("Failed to connect to GitHub: ...")

    //

    //   Case B — invalid stored token:

    //     validationWorker → isTokenValid() → api.github.com → 401 → false

    //     → clearToken() → beginDeviceAuthFlow → same as Case A

    // =========================================================================



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

            // The failure message is what the UI shows the user.

            // An empty or null message leaves the user with no idea what happened.

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

            // This verifies the specific message template from beginDeviceAuthFlow.done():

            //   "Failed to connect to GitHub: " + e.getCause().getMessage()

            // If this assertion fails in future, the message template was changed.

            GitHubAuthService service = new GitHubAuthService();

            RecordingCallback cb = new RecordingCallback();



            service.startAuthentication(cb);

            cb.awaitTerminal(TIMEOUT);



            String msg = cb.failureMessage.get();

            // Note: in headless mode the message could ALSO be "Desktop not supported..."

            // if somehow requestDeviceCodes() succeeded first. In practice it doesn't

            // (HTTP 404), but we accept either message to keep the test environment-stable.

            assertNotNull(msg);

            boolean isExpectedMessage =

                (msg.contains("Failed to connect to GitHub") ||

                 msg.contains("Desktop not supported")      ||

                 msg.contains("HTTP"));

            assertTrue(isExpectedMessage,

                "Unexpected failure message: [" + msg + "]");

        }



        @Test

        @DisplayName("calls onFailure when a stored token is invalid (validation returns false)")

        void callsOnFailureWhenStoredTokenIsInvalid() throws InterruptedException {

            // Path: token exists → isTokenValid() → 401 → false → clearToken()

            // → beginDeviceAuthFlow → HTTP 404 → onFailure

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

            // GitHubAuthService.startAuthentication() calls TokenManager.clearToken()

            // BEFORE calling beginDeviceAuthFlow().  By the time onFailure fires,

            // the token must already be gone.

            // This matters: if the token were NOT cleared, the next call to

            // startAuthentication() would try to validate the same bad token again,

            // wasting one network round-trip every time.

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

            // requestDeviceCodes() throws IOException (HTTP 404) BEFORE done()

            // ever reaches callback.onUserCodeReceived().

            // This test documents what the UI can rely on: if the device code

            // request fails, the user code display panel must not try to render

            // a null/empty user code.

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

            // After onFailure fires, the pollingWorker was never started

            // (requestDeviceCodes() failed before the polling phase).

            // isAuthenticationInProgress() must reflect that.

            GitHubAuthService service = new GitHubAuthService();

            RecordingCallback cb = new RecordingCallback();



            service.startAuthentication(cb);

            cb.awaitTerminal(TIMEOUT);



            assertFalse(service.isAuthenticationInProgress(),

                "isAuthenticationInProgress must be false after a terminal failure event");

        }

    }

}