/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2026 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;
import org.pathvisio.githubplugin.util.TokenManager;

import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link GitHubAuthService}.
 *
 * These tests exercise the class only through its public contract
 * ({@code startAuthentication}, {@code cancelAuthentication},
 * {@code isAuthenticated}, {@code isAuthenticationInProgress}), rather than
 * reflecting into the private request/poll/validate methods directly.
 * Every private code path (device code request, polling, token validation)
 * is reachable and observable through those four public methods, so testing
 * through them exercises the same logic while asserting on behavior instead
 * of implementation.
 *
 * Network calls are never made. {@code TokenManager}, {@code HttpUtil}, and
 * {@code Desktop} are mocked as statics; {@code URL} construction is
 * intercepted so {@code openConnection()} returns a mocked
 * {@code HttpURLConnection} carrying canned GitHub API responses.
 */
class GitHubAuthServiceTest {

	// Must match the private constants in GitHubAuthService exactly.
	private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
	private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";

	@BeforeEach
	void prewarmClasses() {
		// Pre-warming JsonParser avoids a ByteBuddy/Mockito classloader conflict
		// (NoClassDefFoundError for JsonParser) that surfaces when
		// mockConstruction(URL.class) is used in the same JVM as static JSON
		// parsing calls. Same root cause and fix as in GpmlEncoderTest.
		JsonParser.class.getName();
	}

	// ================================================================================
	// Test Helpers
	// ================================================================================

	/**
	 * Callback implementation that records every event so assertions can be
	 * made after the async flow completes. onSuccess and onFailure are
	 * mutually exclusive terminal events, so both count down the same latch.
	 */
	private static class RecordingCallback implements GitHubAuthService.AuthCallback {
		final CountDownLatch userCodeLatch = new CountDownLatch(1);
		final CountDownLatch terminalLatch = new CountDownLatch(1);
		final List<String> statusMessages = new CopyOnWriteArrayList<>();
		volatile String receivedUserCode;
		volatile int receivedExpiresIn;
		volatile String successToken;
		volatile String failureMessage;

		@Override
		public void onUserCodeReceived(String userCode, int expiresIn) {
			receivedUserCode = userCode;
			receivedExpiresIn = expiresIn;
			userCodeLatch.countDown();
		}

		@Override
		public void onStatusUpdate(String message) {
			statusMessages.add(message);
		}

		@Override
		public void onSuccess(String accessToken) {
			successToken = accessToken;
			terminalLatch.countDown();
		}

		@Override
		public void onFailure(String errorMessage) {
			failureMessage = errorMessage;
			terminalLatch.countDown();
		}
	}

	/**
	 * Builds a mocked HttpURLConnection returning the given status code and
	 * response body. getOutputStream() always returns a fresh, discardable
	 * stream so the request-writing code in GitHubAuthService does not fail.
	 */
	private HttpURLConnection mockConnection(int responseCode, String responseBody) throws Exception {
		HttpURLConnection conn = mock(HttpURLConnection.class);
		when(conn.getResponseCode()).thenReturn(responseCode);
		when(conn.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		when(conn.getInputStream()).thenReturn(
				new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
		return conn;
	}

	private String deviceCodeJson(String deviceCode, String userCode, String verificationUri,
			int expiresIn, int interval) {
		return "{"
				+ "\"device_code\":\"" + deviceCode + "\","
				+ "\"user_code\":\"" + userCode + "\","
				+ "\"verification_uri\":\"" + verificationUri + "\","
				+ "\"expires_in\":" + expiresIn + ","
				+ "\"interval\":" + interval
				+ "}";
	}

	private String accessTokenSuccessJson(String token) {
		return "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"scope\":\"repo\"}";
	}

	private String errorJson(String error) {
		return "{\"error\":\"" + error + "\"}";
	}

	// ================================================================================
	// startAuthentication — fresh device flow (no existing token)
	// ================================================================================

	@Test
	@DisplayName("No existing token: full device flow runs and succeeds")
	void startAuthentication_noExistingToken_completesDeviceFlow() throws Exception {
		AtomicInteger pollCallCount = new AtomicInteger(0);

		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<Desktop> desktopMock = mockStatic(Desktop.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);

					// Route the mocked connection based on which endpoint was requested.
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						when(mockUrl.openConnection()).thenReturn(mockConnection(200,
								deviceCodeJson("dev-code-123", "ABCD-1234",
										"https://github.com/login/device", 900, 0)));
					} else if (requestedUrl.equals(ACCESS_TOKEN_URL)) {
						int callNumber = pollCallCount.incrementAndGet();
						if (callNumber == 1) {
							// First poll: user hasn't authorized yet.
							when(mockUrl.openConnection()).thenReturn(
									mockConnection(200, errorJson("authorization_pending")));
						} else {
							// Second poll: user has authorized.
							when(mockUrl.openConnection()).thenReturn(
									mockConnection(200, accessTokenSuccessJson("ghu_testtoken123")));
						}
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);
			// No existing token, so isTokenValid()/HttpUtil is never consulted.
			desktopMock.when(Desktop::isDesktopSupported).thenReturn(false);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.userCodeLatch.await(10, TimeUnit.SECONDS),
					"onUserCodeReceived should fire once device codes are obtained");
			assertEquals("ABCD-1234", callback.receivedUserCode);
			assertEquals(900, callback.receivedExpiresIn);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS),
					"onSuccess should fire once polling receives the access token");
			assertEquals("ghu_testtoken123", callback.successToken);
			assertNull(callback.failureMessage);

			tokenManagerMock.verify(() -> TokenManager.saveToken("ghu_testtoken123"));

			// Desktop wasn't supported, so the manual-visit fallback message
			// should have been surfaced via onStatusUpdate.
			assertTrue(callback.statusMessages.stream()
					.anyMatch(msg -> msg.contains("ABCD-1234")),
					"Fallback status message should include the user code");
		}
	}

	// ================================================================================
	// startAuthentication — existing token branches
	// ================================================================================

	@Test
	@DisplayName("Existing valid token: onSuccess fires immediately, no device flow")
	void startAuthentication_existingValidToken_succeedsDirectly() throws Exception {
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class)) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn("valid-existing-token");

			HttpURLConnection validationConn = mockConnection(200, "");
			httpUtilMock.when(() -> HttpUtil.openAuthenticatedConnection(
							"https://api.github.com/user", "GET", "valid-existing-token"))
					.thenReturn(validationConn);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS));
			assertEquals("valid-existing-token", callback.successToken);
			assertNull(callback.failureMessage);

			tokenManagerMock.verify(() -> TokenManager.clearToken(), never());
			tokenManagerMock.verify(() -> TokenManager.saveToken(org.mockito.ArgumentMatchers.anyString()), never());
		}
	}

	@Test
	@DisplayName("Existing invalid token: token is cleared and device flow begins")
	void startAuthentication_existingInvalidToken_fallsBackToDeviceFlow() throws Exception {
		AtomicInteger pollCallCount = new AtomicInteger(0);

		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<HttpUtil> httpUtilMock = mockStatic(HttpUtil.class);
				MockedStatic<Desktop> desktopMock = mockStatic(Desktop.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						when(mockUrl.openConnection()).thenReturn(mockConnection(200,
								deviceCodeJson("dev-code-999", "WXYZ-9999",
										"https://github.com/login/device", 900, 0)));
					} else if (requestedUrl.equals(ACCESS_TOKEN_URL)) {
						int callNumber = pollCallCount.incrementAndGet();
						if (callNumber == 1) {
							when(mockUrl.openConnection()).thenReturn(
									mockConnection(200, errorJson("authorization_pending")));
						} else {
							when(mockUrl.openConnection()).thenReturn(
									mockConnection(200, accessTokenSuccessJson("ghu_newtoken456")));
						}
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn("expired-token");
			HttpURLConnection validationConn = mockConnection(401, "");
			httpUtilMock.when(() -> HttpUtil.openAuthenticatedConnection(
							"https://api.github.com/user", "GET", "expired-token"))
					.thenReturn(validationConn);
			desktopMock.when(Desktop::isDesktopSupported).thenReturn(false);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS));
			assertEquals("ghu_newtoken456", callback.successToken);

			tokenManagerMock.verify(TokenManager::clearToken);
			tokenManagerMock.verify(() -> TokenManager.saveToken("ghu_newtoken456"));
		}
	}

	// ================================================================================
	// requestDeviceCodes error path (reached via startAuthentication)
	// ================================================================================

	@Test
	@DisplayName("Device code response missing a required field: onFailure fires")
	void startAuthentication_deviceCodeResponseMissingField_causesFailure() throws Exception {
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						// "interval" field is missing.
						String incompleteJson = "{"
								+ "\"device_code\":\"dev-code\","
								+ "\"user_code\":\"CODE-1\","
								+ "\"verification_uri\":\"https://github.com/login/device\","
								+ "\"expires_in\":900"
								+ "}";
						when(mockUrl.openConnection()).thenReturn(mockConnection(200, incompleteJson));
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS));
			assertNull(callback.successToken);
			assertTrue(callback.failureMessage.contains("Failed to connect to GitHub"),
					"Failure message should wrap the underlying connection failure");
		}
	}

	// ================================================================================
	// pollForAccessToken error paths (reached via startAuthentication)
	// ================================================================================

	@Test
	@DisplayName("GitHub returns access_denied: onFailure fires with denial message")
	void startAuthentication_accessDenied_causesFailure() throws Exception {
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<Desktop> desktopMock = mockStatic(Desktop.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						when(mockUrl.openConnection()).thenReturn(mockConnection(200,
								deviceCodeJson("dev-code", "DENY-CODE",
										"https://github.com/login/device", 900, 0)));
					} else if (requestedUrl.equals(ACCESS_TOKEN_URL)) {
						when(mockUrl.openConnection()).thenReturn(
								mockConnection(200, errorJson("access_denied")));
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);
			desktopMock.when(Desktop::isDesktopSupported).thenReturn(false);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS));
			assertNull(callback.successToken);
			assertTrue(callback.failureMessage.contains("Access denied"));
		}
	}

	@Test
	@DisplayName("Device code expires before authorization: onFailure fires with expiry message")
	void startAuthentication_deviceCodeExpired_causesFailure() throws Exception {
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<Desktop> desktopMock = mockStatic(Desktop.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						when(mockUrl.openConnection()).thenReturn(mockConnection(200,
								deviceCodeJson("dev-code", "EXPIRE-CODE",
										"https://github.com/login/device", 900, 0)));
					} else if (requestedUrl.equals(ACCESS_TOKEN_URL)) {
						when(mockUrl.openConnection()).thenReturn(
								mockConnection(200, errorJson("expired_token")));
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);
			desktopMock.when(Desktop::isDesktopSupported).thenReturn(false);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.terminalLatch.await(10, TimeUnit.SECONDS));
			assertNull(callback.successToken);
			assertTrue(callback.failureMessage.contains("expired"));
		}
	}

	// ================================================================================
	// cancelAuthentication / isAuthenticationInProgress
	// ================================================================================

	@Test
	@DisplayName("cancelAuthentication before any flow starts is a safe no-op")
	void cancelAuthentication_noFlowStarted_isNoOp() {
		GitHubAuthService service = new GitHubAuthService();
		// Should not throw, and progress should read false.
		service.cancelAuthentication();
		assertFalse(service.isAuthenticationInProgress());
	}

	@Test
	@DisplayName("cancelAuthentication during polling: KNOWN GAP — callback is never notified")
	void cancelAuthentication_duringPolling_stopsWorker_butCallbackIsNotNotified() throws Exception {
		// NOTE: this test documents the CURRENT behavior, not the intended one.
		// See the accompanying write-up: cancel(true) interrupts the polling
		// worker's Thread.sleep, which puts the underlying FutureTask into a
		// CANCELLED state rather than an EXCEPTIONAL one. done()'s call to
		// get() then throws CancellationException, which is not caught by
		// either of done()'s existing catch blocks (ExecutionException,
		// InterruptedException). The exception is swallowed by the EDT's
		// default handler, so callback.onFailure("Authentication cancelled.")
		// never actually fires today. This test asserts that gap explicitly
		// so it doesn't get "fixed" back to a false negative if the polling
		// logic changes without addressing the underlying cause.
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class);
				MockedStatic<Desktop> desktopMock = mockStatic(Desktop.class);
				MockedConstruction<URL> urlMock = mockConstruction(URL.class, (mockUrl, context) -> {
					String requestedUrl = (String) context.arguments().get(0);
					if (requestedUrl.equals(DEVICE_CODE_URL)) {
						when(mockUrl.openConnection()).thenReturn(mockConnection(200,
								// Long interval and expiry so the worker is still
								// polling (asleep) when we cancel it.
								deviceCodeJson("dev-code", "CANCEL-CODE",
										"https://github.com/login/device", 3600, 2)));
					} else if (requestedUrl.equals(ACCESS_TOKEN_URL)) {
						// Always pending — user never authorizes in this test.
						when(mockUrl.openConnection()).thenReturn(
								mockConnection(200, errorJson("authorization_pending")));
					}
				})) {

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);
			desktopMock.when(Desktop::isDesktopSupported).thenReturn(false);

			RecordingCallback callback = new RecordingCallback();
			GitHubAuthService service = new GitHubAuthService();
			service.startAuthentication(callback);

			assertTrue(callback.userCodeLatch.await(10, TimeUnit.SECONDS),
					"Device codes should be obtained and polling worker started");
			assertTrue(service.isAuthenticationInProgress());

			service.cancelAuthentication();

			// Give the EDT a moment to process the cancellation.
			boolean terminalFired = callback.terminalLatch.await(5, TimeUnit.SECONDS);

			assertFalse(service.isAuthenticationInProgress(),
					"Worker should report done() after cancellation regardless of callback delivery");
			assertFalse(terminalFired,
					"Documents the current gap: onFailure is not invoked on cancellation. "
							+ "If this assertion starts failing, the underlying bug has likely "
							+ "been fixed — update this test to assert onFailure IS called.");
		}
	}

	// ================================================================================
	// isAuthenticated
	// ================================================================================

	@Test
	@DisplayName("isAuthenticated reflects whether TokenManager has a stored token")
	void isAuthenticated_delegatesToTokenManager() {
		try (MockedStatic<TokenManager> tokenManagerMock = mockStatic(TokenManager.class)) {
			GitHubAuthService service = new GitHubAuthService();

			tokenManagerMock.when(TokenManager::getToken).thenReturn("some-token");
			assertTrue(service.isAuthenticated());

			tokenManagerMock.when(TokenManager::getToken).thenReturn(null);
			assertFalse(service.isAuthenticated());
		}
	}
}