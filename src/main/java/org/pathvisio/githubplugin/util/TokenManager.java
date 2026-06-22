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

package org.pathvisio.githubplugin.util;
import java.util.prefs.Preferences;

/**
 * Utility class for managing persistent storage of GitHub authentication tokens.
 * 
 * <p>This class provides a simple interface for saving, retrieving, and clearing
 * GitHub access tokens using the Java {@link Preferences} API. Tokens are stored
 * in the platform-specific preferences system:</p>
 * <ul>
 * <li><strong>Windows:</strong> Stored in the Windows Registry under the current user's registry tree (plaintext)</li>
 * <li><strong>macOS:</strong> Stored in a plist file in the user's Library/Preferences directory (plaintext)</li>
 * <li><strong>Linux:</strong> Stored in the user's home directory under {@code ~/.java/.userPrefs} (plaintext)</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong> The {@link Preferences} API is thread-safe,
 * making all methods in this class safe to call from multiple threads concurrently.</p>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * // Save a token after successful authentication
 * String accessToken = "github_pat_...";
 * TokenManager.saveToken(accessToken);
 * 
 * // Check if a token exists
 * if (TokenManager.hasToken()) {
 *     String token = TokenManager.getToken();
 *     // Use token for API calls
 * }
 * 
 * // Clear the token on logout or token revocation
 * TokenManager.clearToken();
 * </pre>
 * 
 * <p><strong>Token Lifecycle:</strong></p>
 * <ol>
 * <li>After successful GitHub authentication via {@link org.pathvisio.githubplugin.GitHubAuthService},
 * the received access token is stored using {@link #saveToken(String)}</li>
 * <li>On subsequent application startup, the token is retrieved with {@link #getToken()}
 * and validated against GitHub's API</li>
 * <li>If the token is revoked, expired, or no longer valid, it is cleared using {@link #clearToken()}</li>
 * <li>A new authentication flow is initiated to obtain a fresh token</li>
 * </ol>
 * 
 * @author Snehashree Prusty
 * @version 1.0
 * @see org.pathvisio.githubplugin.GitHubAuthService
 * @see Preferences
 */
public class TokenManager 
{
    /**
     * Preferences node for storing GitHub-related application settings.
     * 
     * This static final field holds a reference to the preferences node specific
     * to the TokenManager class, obtained from the current user's preferences tree.
     * All token operations use this preferences node for data persistence.
     */
    private static final Preferences prefs = Preferences.userNodeForPackage(TokenManager.class);
    
    /**
     * The key used to store and retrieve the GitHub access token in the preferences system.
     * 
     * All token operations (save, get, remove) use this constant key to ensure
     * consistent access to the stored token.
     */
    private static final String TOKEN_KEY = "github_token";
    
    /**
     * Saves a GitHub access token to persistent storage.
     * 
     * <p>The token is stored using the Java {@link Preferences} API, which persists
     * the token to the platform-specific credential storage system. This method
     * overwrites any previously stored token.</p>
     * 
     * <p><strong>Note:</strong> While the Preferences API provides platform-specific
     * security (e.g., Windows Registry, macOS Keychain, Linux file permissions),
     * the actual level of encryption varies by platform. For highly sensitive tokens,
     * consider using platform-specific secure storage APIs.</p>
     * 
     * @param token the GitHub access token to store (typically a PAT beginning with "github_pat_")
     * 
     * @see #getToken()
     * @see #clearToken()
     * @see #hasToken()
     */
    
    public static void saveToken(String token) 
    {
        if (token == null) 
        {
            clearToken();
            return;
        }
        prefs.put(TOKEN_KEY, token);
    }
    
    /**
     * Retrieves the stored GitHub access token.
     * 
     * <p>Returns the token previously saved with {@link #saveToken(String)},
     * or {@code null} if no token has been stored or if the token was cleared
     * with {@link #clearToken()}.</p>
     * 
     * @return the stored GitHub access token, or {@code null} if not present
     * 
     * @see #saveToken(String)
     * @see #hasToken()
     */
    public static String getToken() 
    {
        return prefs.get(TOKEN_KEY, null);
    }
    
    /**
     * Clears the stored GitHub access token from persistent storage.
     * 
     * <p>This method removes the token from the preferences system, typically called when:</p>
     * <ul>
     * <li>The user explicitly logs out</li>
     * <li>The token is found to be invalid or revoked during validation</li>
     * <li>The user requests to disconnect their GitHub account</li>
     * </ul>
     * 
     * <p>After calling this method, {@link #getToken()} will return {@code null}
     * and {@link #hasToken()} will return {@code false} until a new token is saved.</p>
     * 
     * @see #saveToken(String)
     * @see #getToken()
     * @see #hasToken()
     */
    public static void clearToken() 
    {
        prefs.remove(TOKEN_KEY);
    }
    
    /**
     * Checks whether a GitHub access token is currently stored.
     * 
     * <p>This is a convenience method that performs a fast local check without
     * making any network calls. A return value of {@code true} indicates a token
     * exists in storage, but does not guarantee the token is valid on GitHub.</p>
     * 
     * <p>Token validity should be verified by the authentication service (typically
     * via an API call to GitHub's user endpoint) before using the token for actual
     * API requests.</p>
     * 
     * @return {@code true} if a token is stored, {@code false} otherwise
     * 
     * @see #getToken()
     * @see #saveToken(String)
     */
    public static boolean hasToken() 
    {
        // String token = getToken();
        return getToken() != null && !getToken().trim().isEmpty(); 
    }
}
