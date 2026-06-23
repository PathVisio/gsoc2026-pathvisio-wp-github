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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.swing.SwingWorker;
import org.pathvisio.githubplugin.util.TokenManager;
import java.awt.Desktop;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.pathvisio.githubplugin.util.HttpUtil;
import org.pathvisio.githubplugin.util.JsonParser;

/**
 * Service class for managing GitHub OAuth 2.0 Device Flow authentication.
 * 
 * This class handles the complete authentication lifecycle for GitHub OAuth using the 
 * device code flow, which is suitable for applications that cannot receive HTTP callbacks.
 * The authentication flow consists of the following steps:
 * <ol>
 * <li>Request device and user codes from GitHub's device code endpoint</li>
 * <li>Display the user code to the end user with instructions to visit github.com/login/device</li>
 * <li>Poll GitHub's token endpoint at regular intervals until authorization completes or expires</li>
 * <li>Validate and store the received access token for use in subsequent API requests</li>
 * </ol>
 * 
 * <p>All network operations are performed asynchronously using {@link SwingWorker} to prevent
 * blocking the user interface. Authentication results are delivered via callbacks that execute
 * on the Event Dispatch Thread (EDT), making it safe to update UI components directly.</p>
 * 
 * <p>The service stores tokens using {@link TokenManager} for persistence across sessions.
 * Before initiating a new authentication flow, it checks for existing tokens and validates
 * them with GitHub's API. Only expired or invalid tokens trigger a new authentication flow.</p>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * GitHubAuthService authService = new GitHubAuthService();
 * authService.startAuthentication(new GitHubAuthService.AuthCallback() {
 *     &#64;Override
 *     public void onUserCodeReceived(String userCode, int expiresIn) {
 *         // Display userCode to user, expires in expiresIn seconds
 *     }
 *
 *     &#64;Override
 *     public void onStatusUpdate(String message) {
 *         // Update UI with current authentication status
 *     }
 *
 *     &#64;Override
 *     public void onSuccess(String accessToken) {
 *         // Authentication successful, token ready for API calls
 *     }
 *
 *     &#64;Override
 *     public void onFailure(String errorMessage) {
 *         // Handle authentication failure
 *     }
 * });
 * </pre>
 * 
 * <p><strong>Configuration:</strong> The {@code CLIENT_ID} constant must be set to a valid
 * GitHub OAuth application client ID before authentication can succeed. This ID should be
 * obtained by registering the application at github.com/settings/applications/new.</p>
 * 
 * @author Snehashree Prusty
 * @version 1.0
 * @see AuthCallback
 * @see SwingWorker
 * @see TokenManager
 */
public class GitHubAuthService {
	/**
	 * GitHub OAuth application client ID.
	 * 
	 * Must be configured with a valid GitHub OAuth app client ID for authentication to work.
	 */
	private static final String CLIENT_ID = "Ov23lijbeHfxYZjidh2J"; //it is a public client id, so it is safe to keep it here.

	/**
	 * GitHub API endpoint for requesting device and user codes.
	 * 
	 * This is the first step in the device code OAuth flow.
	 */
	private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";

	/**
	 * GitHub API endpoint for polling and exchanging device code for access token.
	 * 
	 * This endpoint is used for polling authorization status during the authentication flow.
	 */
	private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";

	/**
	 * HTTP connection timeout in milliseconds.
	 */
	private static final int CONNECT_TIMEOUT_MS = 10_000;

	/**
	 * HTTP read timeout in milliseconds.
	 */
	private static final int READ_TIMEOUT_MS = 10_000;

	/**
	 * Device code obtained from GitHub during the authentication flow.
	 * 
	 * Used to poll for access token and must be kept confidential.
	 */
	private String deviceCode;

	/**
	 * Polling interval in seconds.
	 * 
	 * Adjusted dynamically if GitHub returns a "slow_down" error to respect rate limits.
	 */
	private int interval;

	/**
	 * Background worker for polling GitHub's access token endpoint.
	 * 
	 * May be null if no polling is currently in progress.
	 */
	private SwingWorker<String, String> pollingWorker;

	// ================================================================================
	// Inner Classes
	// ================================================================================

	/**
	 * Immutable data class representing the response from GitHub's device code endpoint.
	 * 
	 * Contains all necessary information for the client to guide the user through the
	 * authorization process and to poll for the access token.
	 */
	static class DeviceCodeResponse {
		private String deviceCode;
		private String userCode;
		private String verificationUri;
		private int expiresIn;
		private int interval;

		/**
		 * Constructs a DeviceCodeResponse with all required fields.
		 * 
		 * @param deviceCode the device code used to poll for token (40 alphanumeric characters)
		 * @param userCode the user-friendly code to display for manual entry at github.com/login/device
		 * @param verificationUri the URI where the user enters the device code
		 * @param expiresIn the validity period of the codes in seconds
		 * @param interval the minimum polling interval in seconds recommended by GitHub
		 */
		public DeviceCodeResponse(String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {
			this.deviceCode = deviceCode;
			this.userCode = userCode;
			this.verificationUri = verificationUri;
			this.expiresIn = expiresIn;
			this.interval = interval;
		}

		/**
		 * Returns the device code.
		 * 
		 * @return the device code string
		 */
		public String getDeviceCode() {
			return deviceCode;
		}

		/**
		 * Returns the user-friendly code to be entered at the verification URI.
		 * 
		 * @return the user code string
		 */
		public String getUserCode() {
			return userCode;
		}

		/**
		 * Returns the URI where the user should enter the device code.
		 * 
		 * @return the verification URI string
		 */
		public String getVerificationUri() {
			return verificationUri;
		}

		/**
		 * Returns the validity period of the codes.
		 * 
		 * @return the expiration time in seconds
		 */
		public int getExpiresIn() {
			return expiresIn;
		}

		/**
		 * Returns the recommended polling interval.
		 * 
		 * @return the polling interval in seconds
		 */
		public int getInterval() {
			return interval;
		}
	}

	/**
	 * Callback interface for receiving authentication flow events.
	 * 
	 * All callback methods are invoked on the Event Dispatch Thread (EDT),
	 * making it safe to update UI components directly. Implementations should
	 * return quickly to avoid blocking the EDT.
	 */
	public interface AuthCallback {
		/**
		 * Called when the user code has been successfully obtained from GitHub.
		 * 
		 * The UI should display the {@code userCode} to the user with clear instructions
		 * to visit github.com/login/device and enter the code within the expiration window.
		 * 
		 * @param userCode the user-friendly code for manual entry at the verification URI
		 * @param expiresIn the validity period of the code in seconds
		 */
		void onUserCodeReceived(String userCode, int expiresIn);

		/**
		 * Called periodically during the polling phase to update UI status.
		 * 
		 * Used to inform the user that the authentication flow is still waiting
		 * for their authorization response.
		 * 
		 * @param message a descriptive status message
		 */
		void onStatusUpdate(String message);

		/**
		 * Called when authentication succeeds and a valid access token is obtained.
		 * 
		 * The access token has been validated and stored. The token can be used
		 * for subsequent GitHub API requests.
		 * 
		 * @param accessToken the valid GitHub access token
		 */
		void onSuccess(String accessToken);

		/**
		 * Called when authentication fails or is cancelled.
		 * 
		 * Possible failure reasons include:
		 * <ul>
		 * <li>Device code expired before user authorization</li>
		 * <li>User explicitly denied the authorization request</li>
		 * <li>Network connectivity issues</li>
		 * <li>Invalid or missing CLIENT_ID configuration</li>
		 * </ul>
		 * 
		 * @param errorMessage a description of the failure reason
		 */
		void onFailure(String errorMessage);
	}

	// ================================================================================
	// Private Helper Methods
	// ================================================================================
	
	/**
	 * Validates an access token by making a test request to the GitHub API.
	 * 
	 * Performs a GET request to {@code https://api.github.com/user} using the provided token.
	 * If the request succeeds with HTTP 200 OK, the token is considered valid.
	 * 
	 * <p><strong>Note:</strong> This method should be called from a background thread
	 * to avoid blocking the UI.</p>
	 * 
	 * @param token the access token to validate
	 * @return {@code true} if the token is valid, {@code false} otherwise
	 */
	private boolean isTokenValid(String token) {
        HttpURLConnection conn = null; 
        try {
           
            conn = HttpUtil.openAuthenticatedConnection("https://api.github.com/user", "GET", token); 
            int responseCode = conn.getResponseCode();
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
	

	/**
	 * Requests device and user codes from GitHub's device code endpoint.
	 * 
	 * This is the first step of the device code OAuth flow. GitHub returns a device code
	 * (used for polling) and a user code (displayed to the user).
	 * 
	 * @return a {@link DeviceCodeResponse} containing the device code, user code, and metadata
	 * @throws IOException if the HTTP request fails or the response is invalid
	 */
	private DeviceCodeResponse requestDeviceCodes() throws IOException {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(DEVICE_CODE_URL);
			conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			String requestBody = "client_id=" + CLIENT_ID + "&scope=repo";

			// Try with resources to ensure stream is closed properly
			try (OutputStream os = conn.getOutputStream()) {
				os.write(requestBody.getBytes(StandardCharsets.UTF_8));
			}
			int statusCode = conn.getResponseCode();
			if (statusCode != HttpURLConnection.HTTP_OK) {
				throw new IOException("Failed to request device code: HTTP " + statusCode);
			}

			// Read response body in full
			StringBuilder response = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					response.append(line);
				}
			}

			String json = response.toString();
			String deviceCode = JsonParser.extractValue(json, "device_code");
			String userCode = JsonParser.extractValue(json, "user_code");
			String verificationUri = JsonParser.extractValue(json, "verification_uri");
			String intervalStr = JsonParser.extractValue(json, "interval");
			String expiresInStr = JsonParser.extractValue(json, "expires_in");

			if (deviceCode == null || userCode == null || verificationUri == null || intervalStr == null || expiresInStr == null) {
				throw new IOException("Invalid response from GitHub: missing required fields");
			}
			int interval = Integer.parseInt(intervalStr);
			int expiresIn = Integer.parseInt(expiresInStr);
			return new DeviceCodeResponse(deviceCode, userCode, verificationUri, expiresIn, interval);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/**
	 * Polls GitHub's access token endpoint for the authorization result.
	 * 
	 * This method should be called repeatedly at intervals specified by the initial
	 * device code response, or adjusted based on GitHub's "slow_down" error responses.
	 * 
	 * <p>Possible outcomes:
	 * <ul>
	 * <li>Returns the access token if the user has authorized the request</li>
	 * <li>Returns {@code null} if authorization is still pending or a non-fatal error occurs</li>
	 * <li>Throws {@link IOException} for expired or denied requests</li>
	 * </ul>
	 * 
	 * @return the access token if authorization is successful, {@code null} if still pending
	 * @throws IOException if the device code has expired, access was denied, or a critical error occurs
	 */
	private String pollForAccessToken() throws IOException {
		HttpURLConnection conn = null;
		try {
			URL url = new URL(ACCESS_TOKEN_URL);
			conn = (HttpURLConnection) url.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);

			String requestBody = "client_id=" + CLIENT_ID
				+ "&device_code=" + deviceCode
				+ "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

			try (OutputStream os = conn.getOutputStream()) {
				os.write(requestBody.getBytes(StandardCharsets.UTF_8));
			}

			int statusCode = conn.getResponseCode();
			if (statusCode != HttpURLConnection.HTTP_OK) {
				throw new IOException("GitHub access token poll failed: HTTP " + statusCode);
			}

			StringBuilder response = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					response.append(line);
				}
			}

			String json = response.toString();
			String accessToken = JsonParser.extractValue(json, "access_token");
			String error = JsonParser.extractValue(json, "error");

			if (accessToken != null) {
				return accessToken;
			} else if ("authorization_pending".equals(error)) {
				return null;
			} else if ("slow_down".equals(error)) {
				// GitHub is being hit too fast, RFC 8628 requires adding 5 seconds interval
				interval += 5;
				return null;
			} else if ("expired_token".equals(error)) {
				// Device code window closed, user did not enter the code in time
				throw new IOException("Authentication code expired. Please sign in again.");
			} else if ("access_denied".equals(error)) {
				// User saw the code and explicitly clicked deny
				throw new IOException("Access denied. User rejected the authorization request.");
			} 
			else if (error != null) {
			 throw new IOException("Unexpected error from GitHub: " + error);
            }
			else {
				return null;
			}
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

/**
 * Creates but does not execute a {@link SwingWorker} that polls
 * GitHub's access token endpoint until authorization completes,
 * the device code expires, or the user cancels.
 *
 * <p>The polling loop respects GitHub's required interval between
 * requests per RFC 8628. If GitHub returns a {@code slow_down}
 * response, the interval is increased by 5 seconds automatically.</p>
 *
 * <p>The caller is responsible for calling {@link SwingWorker#execute()}
 * on the returned worker. This separation allows the caller to store
 * the worker reference before starting it.</p>
 *
 * @param callback the {@link AuthCallback} to notify with results
 * @param expiresIn the device code validity period in seconds
 * @return a configured but unstarted {@link SwingWorker}
 */
private SwingWorker<String, String> createPollingWorker(AuthCallback callback, int expiresIn)
{
   return new SwingWorker<String, String>() {
    @Override
    protected String doInBackground() throws Exception {

        long expiryTime = System.currentTimeMillis() + expiresIn * 1000L;
        while (!isCancelled())
        {
            if (System.currentTimeMillis() > expiryTime)
            {
                throw new IOException("Code for authentication has expired. Please try again.");
            }
            String token = pollForAccessToken();
            if (token != null) return token;
            publish("Waiting for user authorization...");
            Thread.sleep(interval*1000L);
        }
        return null;
    }
        @Override
        protected void process(List<String> chunks){
            String latestMessage = chunks.get(chunks.size()-1);
            callback.onStatusUpdate(latestMessage);
        }
        @Override
        protected void done() {
            try {
                String token = get();
                if (token != null)
                {
                TokenManager.saveToken(token);
                callback.onSuccess(token);
                }
                else
                {
                    callback.onFailure("Authentication cancelled.");
                }
            }
            catch (ExecutionException e)
            {
               callback.onFailure("Authentication failed: " + e.getCause().getMessage()); 
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                callback.onFailure("Authentication interrupted.");
            }
        }
    };
}
/**
 * Initiates the GitHub Device Authorization flow.
 *
 * <p>Requests device and user codes from GitHub, displays the user
 * code via callback, opens the user's default browser to the
 * verification URI, and starts the polling worker.</p>
 *
 * <p>Called when no valid token exists in local storage.</p>
 *
 * <p>Must be called from the EDT. All network operations are
 * performed on a background thread internally.</p>
 *
 * @param callback the {@link AuthCallback} to notify with flow events
 */
	private void beginDeviceAuthFlow(AuthCallback callback) {
		SwingWorker <DeviceCodeResponse, Void> setUpWorker = new SwingWorker<DeviceCodeResponse, Void>()
        {
            @Override
            protected DeviceCodeResponse doInBackground() throws Exception {
                return requestDeviceCodes();
        }
            @Override
            protected void done()
            {
                try {
                    DeviceCodeResponse response = get();
                    deviceCode = response.getDeviceCode();
                    interval = response.getInterval();
                    callback.onUserCodeReceived(response.getUserCode(), response.getExpiresIn());
                    
                    if (Desktop.isDesktopSupported())
                    {
                        Desktop desktop = Desktop.getDesktop();
                        if (desktop.isSupported(Desktop.Action.BROWSE))
                        {
							try
							{
							   desktop.browse(new URI(response.getVerificationUri()));
							}
							catch (IOException e)
							{
								callback.onFailure("Failed to open browser. Please visit: " + response.getVerificationUri());
                                 return;
                             }
						}
                        else
                        {
                          callback.onFailure("Unable to open browser. Please navigate to " + response.getVerificationUri() + " and enter the code: " + response.getUserCode());
                          return;
                        }
                    }
                    else
                    {
                        callback.onFailure("Desktop not supported on this system. " +"Please visit: " + response.getVerificationUri());
                        return;
                    }

                     pollingWorker = createPollingWorker(callback, response.getExpiresIn());
                     pollingWorker.execute();
                }
                catch (ExecutionException e)
                {
                    callback.onFailure("Failed to connect to GitHub: " + e.getCause().getMessage());
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    callback.onFailure("Connection to GitHub was interrupted.");
                }
                catch (URISyntaxException e)
                {
                    callback.onFailure("Invalid verification URI provided by GitHub: " + e.getMessage());
                }
            }
};
    setUpWorker.execute();
    }

	// ================================================================================
	// Public Authentication Methods
	// ================================================================================

 /**
  * Cancels the ongoing GitHub Device Authorization polling process.
  *
  * <p>This method is safe to call at any time. If no authentication
  * is currently in progress, or if the polling worker has already
  * completed, this method does nothing.</p>
  *
  * <p>Calling this method interrupts any active {@link Thread#sleep}
  * in the polling loop, ensuring immediate cancellation rather than
  * waiting for the current sleep interval to expire.</p>
  *
  * <p>Must be called from the Event Dispatch Thread (EDT).</p>
  */
    public void cancelAuthentication()
    {
        if(pollingWorker != null && !pollingWorker.isDone())
        {
            pollingWorker.cancel(true);
        }
    }
/**
 * Returns whether a GitHub authentication session is currently
 * in progress.
 *
 * <p>This method checks whether the polling worker is active —
 * i.e., the user has been shown a device code and the plugin is
 * waiting for them to authorize in their browser.</p>
 *
 * <p>Intended for use by the UI layer to manage button states.
 * For example, the Cancel button should only be enabled when
 * this method returns {@code true}.</p>
 *
 * <p>This method is safe to call on the EDT — no network calls
 * are made.</p>
 *
 * @return {@code true} if polling is active, {@code false} otherwise
 */
    public boolean isAuthenticationInProgress()
    {
        return pollingWorker != null && !pollingWorker.isDone();
    }   
    /**
 * Returns whether the user has a stored authentication token.
 *
 * <p>This method performs a fast local check against the operating
 * system's credential storage via {@link TokenManager}. It does
 * not make any network calls and is safe to call on the EDT.</p>
 *
 * <p>A return value of {@code true} indicates a token exists in
 * storage but does not guarantee the token is still valid on GitHub.
 * Token validity is verified lazily — a revoked token will be
 * detected and cleared when {@link #startAuthentication} is next
 * called.</p>
 *
 * <p>Intended for use on application startup to determine whether
 * to show the login screen or proceed directly to the contribution
 * dashboard.</p>
 *
 * @return {@code true} if a token exists in local storage,
 *         {@code false} if no token is stored
 */
public boolean isAuthenticated()
{
    return TokenManager.getToken() != null;
}

	/**
	 * Starts the GitHub authentication process using the device code flow.
	 * 
	 * This method first checks for an existing token and validates it. If the token is valid,
	 * it immediately invokes {@code callback.onSuccess} with the token. If the token is invalid
	 * or not present, it initiates the device code flow by requesting new codes and guiding the
	 * user through the authorization process.
	 * 
	 * All operations are performed asynchronously to avoid blocking the UI. Callback methods are
	 * invoked on the EDT, making it safe to update UI components directly.
	 * 
	 * @param callback the {@link AuthCallback} to receive authentication events and results
	 */
	public void startAuthentication(AuthCallback callback) {
		String existingToken = TokenManager.getToken();
		if (existingToken != null) {
			// Needs to be validated with GitHub API, must be done in background thread
			SwingWorker<Boolean, Void> validationWorker = new SwingWorker<Boolean, Void>() {
				@Override
				protected Boolean doInBackground() throws Exception {
					return isTokenValid(existingToken);
				}

				@Override
				protected void done() {
					try {
						if (get()) {
							callback.onSuccess(existingToken);
						} else {
							TokenManager.clearToken();
							beginDeviceAuthFlow(callback);
						}
					} catch (Exception e) {
						// Validation failed, start device flow.
						// Might be network error, treat as invalid token.
						TokenManager.clearToken();
						beginDeviceAuthFlow(callback);
					}
				}
			};
			validationWorker.execute();
		} else {
			beginDeviceAuthFlow(callback);
		}
	}
}
