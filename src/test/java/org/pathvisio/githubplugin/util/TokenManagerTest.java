package org.pathvisio.githubplugin.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TokenManager.
 *
 * -------------------------------------------------------------------------
 * IMPORTANT: TokenManager writes to the REAL operating-system Preferences
 * store (Windows Registry / macOS Keychain / ~/.java/.userPrefs on Linux).
 * These are NOT in-memory: data persists across JVM restarts.
 *
 * @BeforeEach and @AfterEach both call clearToken() to guarantee a clean
 * slate regardless of test order or prior failures.  Never remove them.
 *
 * WHY BOTH BEFORE AND AFTER?
 *   @BeforeEach alone: a crashed test leaves dirty state for the next run.
 *   @AfterEach alone:  a prior dirty state pollutes the first test.
 *   Both together:     hermetic in both directions.
 * -------------------------------------------------------------------------
 *
 * CLASS DESIGN NOTES (relevant to what we test)
 * -----------------------------------------------
 * TokenManager is a static-only utility over java.util.Preferences.
 * There is no dependency injection, so tests go against the real store.
 *
 * Two known limitations discovered during probe testing (see comments on
 * individual tests):
 *   1. saveToken(null)  → NullPointerException from Preferences.put()
 *   2. saveToken("")    → hasToken() returns true for an empty token value
 *
 * These tests document existing behaviour.  They are not meant to imply
 * the behaviour is correct — that is a call for the maintainer.
 */
@DisplayName("TokenManager")
class TokenManagerTest {

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    @BeforeEach
    void ensureCleanSlate() {
        TokenManager.clearToken();
    }

    @AfterEach
    void cleanUpAfterTest() {
        TokenManager.clearToken();
    }

    // =========================================================================
    // SECTION 1 — Initial / empty state
    //
    // Verifies the invariant that "no token stored" is observable from every
    // public method.  These are the baseline tests everything else depends on.
    // =========================================================================

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

    // =========================================================================
    // SECTION 2 — saveToken and getToken round-trip
    //
    // The core read-write contract: what goes in must come back out unchanged.
    // Each test saves a distinct token shape to verify no transformation occurs.
    // =========================================================================

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

    // =========================================================================
    // SECTION 3 — clearToken
    //
    // clearToken() must reliably erase the stored token.  GitHubAuthService
    // calls clearToken() when it detects a revoked/invalid token, so a failed
    // clear would lock the user into a broken auth state indefinitely.
    // =========================================================================

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
            // Internally this calls Preferences.remove() on a key that doesn't exist;
            // probe testing confirmed the JDK treats this as a no-op, not an error.
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

    // =========================================================================
    // SECTION 4 — hasToken
    //
    // hasToken() is documented as a fast local check to avoid a network call.
    // Its only job is to be consistent with getToken() != null.
    // =========================================================================

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

   // =========================================================================
    // SECTION 5 — Defensive Guard Verification
    //
    // Verifies that TokenManager gracefully handles edge cases like null and
    // empty string inputs rather than throwing internal platform exceptions.
    // =========================================================================

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