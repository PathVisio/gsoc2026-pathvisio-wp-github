package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TokenManager}.
 *
 * <p>These tests provide comprehensive coverage of TokenManager's static methods,
 * ensuring reliable storage and retrieval of GitHub authentication tokens from the
 * operating-system Preferences store. This test suite covers:</p>
 * <ul>
 * <li>Initial/empty state behavior</li>
 * <li>Token save and retrieval round-trips with various token formats</li>
 * <li>Token clearing and idempotency</li>
 * <li>Consistency between hasToken() and getToken()</li>
 * <li>Defensive handling of edge cases (null, empty strings, whitespace)</li>
 * </ul>
 *
 * <p><strong>Design Note:</strong> TokenManager is a static-only utility over
 * java.util.Preferences with no dependency injection, so these tests execute
 * against the real OS Preferences store (Windows Registry / macOS Keychain /
 * ~/.java/.userPrefs on Linux). Both @BeforeEach and @AfterEach call clearToken()
 * to ensure hermetic test execution: @BeforeEach handles cleanup from prior failures,
 * @AfterEach prevents test pollution across runs.</p>
 *
 * <p>This test suite ensures TokenManager behaves correctly under all expected
 * conditions and serves as a specification for future maintenance and refactoring.</p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 */
@DisplayName("TokenManager")
class TokenManagerTest {

    @BeforeEach
    void ensureCleanSlate() {
        TokenManager.clearToken();
    }

    @AfterEach
    void cleanUpAfterTest() {
        TokenManager.clearToken();
    }

    /**
     * Tests for initial state when no token has been saved.
     * Verifies the invariant that "no token stored" is observable from every public method.
     */
    @Nested
    @DisplayName("Initial state (no token stored)")
    class InitialState {

        @Test
        @DisplayName("getToken returns null when no token has been saved")
        void getTokenReturnsNullWhenNothingStored() {
            // @BeforeEach has already called clearToken(), so storage is empty.
            // This is the most fundamental contract: null means "not authenticated".
            assertNull(TokenManager.getToken());
        }

        @Test
        @DisplayName("hasToken returns false when no token has been saved")
        void hasTokenReturnsFalseWhenNothingStored() {
            assertFalse(TokenManager.hasToken());
        }
    }

    /**
     * Tests for the saveToken/getToken round-trip behavior.
     * The core read-write contract: what goes in must come back out unchanged.
     */
    @Nested
    @DisplayName("saveToken / getToken round-trip")
    class SaveAndGet {

        @Test
        @DisplayName("getToken returns the exact value that was saved")
        void getTokenReturnsExactSavedValue() {
            TokenManager.saveToken("gho_test_token");
            assertEquals("gho_test_token", TokenManager.getToken());
        }

        @Test
        @DisplayName("getToken returns a realistic GitHub PAT format unchanged")
        void getTokenRoundTripsRealisticToken() {
            // Real GitHub OAuth tokens look like this.  Verifies the Preferences
            // store doesn't truncate, trim, or transform the value.
            String realisticToken = "gho_16C7e42F292c6912E7710c838347Ae178B4a";
            TokenManager.saveToken(realisticToken);
            assertEquals(realisticToken, TokenManager.getToken());
        }

        @Test
        @DisplayName("getToken returns token value containing underscores and hyphens")
        void getTokenRoundTripsTokenWithSpecialChars() {
            // GitHub tokens use underscores; some older formats use hyphens.
            // Preferences should store these verbatim.
            TokenManager.saveToken("github_pat_abc-123_DEF");
            assertEquals("github_pat_abc-123_DEF", TokenManager.getToken());
        }

        @Test
        @DisplayName("calling saveToken twice keeps the second value, discarding the first")
        void secondSaveOverwritesFirst() {
            // TEACHING POINT: this covers the mutation path — saves are not
            // accumulated.  The second token should completely replace the first.
            // This matters in GitHubAuthService: re-authentication must update
            // the stored token, not silently fail.
            TokenManager.saveToken("first_token");
            TokenManager.saveToken("second_token");
            assertEquals("second_token", TokenManager.getToken());
        }

        @Test
        @DisplayName("getToken after save does not return null")
        void getTokenAfterSaveIsNotNull() {
            TokenManager.saveToken("any_token");
            assertNotNull(TokenManager.getToken());
        }
    }

    /**
     * Tests for token clearing behavior and idempotency.
     * Ensures clearToken() reliably erases the stored token without side effects.
     */
    @Nested
    @DisplayName("clearToken")
    class ClearToken {

        @Test
        @DisplayName("getToken returns null after clearToken is called")
        void getTokenReturnsNullAfterClear() {
            TokenManager.saveToken("token_to_clear");
            TokenManager.clearToken();
            assertNull(TokenManager.getToken());
        }

        @Test
        @DisplayName("hasToken returns false after clearToken is called")
        void hasTokenReturnsFalseAfterClear() {
            TokenManager.saveToken("token_to_clear");
            TokenManager.clearToken();
            assertFalse(TokenManager.hasToken());
        }

        @Test
        @DisplayName("clearToken does not throw when no token is stored")
        void clearTokenOnEmptyStoreDoesNotThrow() {
            // @BeforeEach already cleared, so storage is already empty.
            // This verifies clearToken() is idempotent — safe to call defensively.
            assertDoesNotThrow(TokenManager::clearToken);
        }

        @Test
        @DisplayName("clearToken can be called multiple times without throwing")
        void clearTokenIsIdempotent() {
            TokenManager.saveToken("token");
            assertDoesNotThrow(() -> {
                TokenManager.clearToken();
                TokenManager.clearToken();  // second call on empty store
                TokenManager.clearToken();  // third for good measure
            });
        }

        @Test
        @DisplayName("token can be re-saved after clearToken")
        void canSaveAgainAfterClear() {
            // Validates the full clear → re-auth cycle that GitHubAuthService uses
            // when a token is found to be invalid:
            //   1. Detect 401 from GitHub
            //   2. clearToken()
            //   3. beginDeviceAuthFlow()
            //   4. saveToken(newToken)
            TokenManager.saveToken("original_token");
            TokenManager.clearToken();
            TokenManager.saveToken("new_token");
            assertEquals("new_token", TokenManager.getToken());
        }
    }

    /**
     * Tests for hasToken consistency with getToken behavior.
     * hasToken() should always be consistent with getToken() != null.
     */
    @Nested
    @DisplayName("hasToken consistency")
    class HasToken {

        @Test
        @DisplayName("hasToken returns true after a token is saved")
        void hasTokenReturnsTrueAfterSave() {
            TokenManager.saveToken("any_token");
            assertTrue(TokenManager.hasToken());
        }

        @Test
        @DisplayName("hasToken is consistent with getToken being non-null")
        void hasTokenIsConsistentWithGetToken() {
            // hasToken() is defined as getToken() != null.
            // These two expressions must always agree.  Test both states.

            // State 1: no token
            boolean hasBeforeSave = TokenManager.hasToken();
            boolean getIsNullBeforeSave = (TokenManager.getToken() == null);
            assertEquals(!getIsNullBeforeSave, hasBeforeSave,
                "hasToken() must equal (getToken() != null) before save");

            // State 2: token present
            TokenManager.saveToken("consistency_token");
            boolean hasAfterSave = TokenManager.hasToken();
            boolean getIsNullAfterSave = (TokenManager.getToken() == null);
            assertEquals(!getIsNullAfterSave, hasAfterSave,
                "hasToken() must equal (getToken() != null) after save");
        }
    }

    /**
     * Tests for defensive handling of edge case inputs.
     * Verifies that TokenManager gracefully handles null, empty strings, and whitespace.
     */
    @Nested
    @DisplayName("Defensive guards")
    class DefensiveGuards {

        @Test
        @DisplayName("saveToken with null handles it safely by clearing the token node")
        void saveTokenNullClearsTokenSafely() {
            // Save a token first so we can verify null clears it
            TokenManager.saveToken("existing_token");
            
            // Call with null — should act as a clear operation instead of crashing
            TokenManager.saveToken(null);
            
            assertNull(TokenManager.getToken());
            assertFalse(TokenManager.hasToken());
        }

        @Test
        @DisplayName("saveToken with empty string prevents hasToken from returning true")
        void saveEmptyStringMakesHasTokenReturnFalse() {
            // An empty string should not count as a valid authenticated state
            TokenManager.saveToken("");
            
            assertFalse(TokenManager.hasToken(), "hasToken() should return false for an empty token");
            assertEquals("", TokenManager.getToken(), "getToken() still returns the raw empty string value");
        }

        @Test
        @DisplayName("hasToken returns false for a token string consisting only of whitespace")
        void saveWhitespaceStringMakesHasTokenReturnFalse() {
            // A string with just spaces is also useless for API calls
            TokenManager.saveToken("   ");
            
            assertFalse(TokenManager.hasToken(), "hasToken() should return false for whitespace-only tokens");
        }
    }
}
