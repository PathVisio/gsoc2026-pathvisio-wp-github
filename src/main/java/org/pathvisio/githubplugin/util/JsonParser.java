/*******************************************************************************
 * PathVisio, a tool for data visualization and analysis using biological pathways
 * Copyright 2006-2024 PathVisio
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.pathvisio.githubplugin.util;
 
/**
 * Utility class for parsing flat JSON responses from the GitHub REST API.
 *
 * <p>This parser handles the simple, flat JSON structures returned by GitHub's
 * authentication and repository endpoints. It does not support nested objects,
 * arrays, or other complex JSON structures — use a full JSON library such as
 * Gson or Jackson if those are required.</p>
 *
 * <p>This class is intentionally kept separate from authentication logic
 * so that JSON parsing can be tested independently without requiring
 * network connectivity or GitHub credentials.</p>
 *
 * <p>This class cannot be instantiated — all methods are static.</p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 */
public final class JsonParser 
{
    /**
     * Private constructor — prevents instantiation.
     * All methods are static. This class is a utility namespace only.
     */
    private JsonParser()
    {
        throw new UnsupportedOperationException("JsonParser is a utility class and cannot be instantiated.");
    }
    /**
     * Extracts a single value from a flat JSON string by key.
     *
     * <p>Handles both quoted string values and unquoted numeric or boolean values.
     * Whitespace between the colon and value is ignored. Key matching is exact —
     * searching for {@code "code"} will not match {@code "device_code"}.</p>
     *
     * <p>Example — string value:</p>
     * <pre>
     * String json = "{\"user_code\":\"WBJI-BCAS\",\"interval\":5}";
     * String code = JsonParser.extractValue(json, "user_code"); // "WBJI-BCAS"
     * </pre>
     *
     * <p>Example — numeric value:</p>
     * <pre>
     * String json = "{\"interval\":5,\"expires_in\":900}";
     * String interval = JsonParser.extractValue(json, "interval"); // "5"
     * int parsed = Integer.parseInt(interval);                     // 5
     * </pre>
     *
     * <p><strong>Limitations:</strong> Does not support nested objects, arrays,
     * escaped quotes inside string values, or null JSON values. Suitable only
     * for parsing GitHub REST API responses which are flat and well-structured.</p>
     *
     * @param json the raw JSON string to parse — must not be {@code null}
     * @param key  the JSON key to look up — without surrounding quotes
     * @return the extracted value as a {@code String}, or {@code null} if the
     *         key is not present or the JSON is malformed
     */
    public static String extractValue(String json, String key)
    {
		String searchKey = "\"" + key + "\":";
		int keyIndex = json.indexOf(searchKey);
		if (keyIndex == -1) {
			return null;
		}

		int valueStart = keyIndex + searchKey.length();
		// Skip whitespace between colon and value
		while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
			valueStart++;
		}
		if (valueStart >= json.length()) {
			return null;
		}

		if (json.charAt(valueStart) == '"') {
			valueStart++;
			int valueEnd = json.indexOf('"', valueStart);
			if (valueEnd == -1) {
				return null;
			}
			return json.substring(valueStart, valueEnd);
		} else {
			int valueEnd = json.indexOf(",", valueStart);
			if (valueEnd == -1) {
				valueEnd = json.indexOf("}", valueStart);
			}
			if (valueEnd == -1) {
				return null;
			}
			return json.substring(valueStart, valueEnd).trim();
		}
	}
    
    /**
     * Parses the "sha" value from a GitHub API response.
     *
     * <p>Specifically designed to extract the "sha" field from the JSON response
     * returned by GitHub's repository content API when checking for existing files.</p>
     *
     * <p>Example:</p>
     * <pre>
     * String jsonResponse = "{\"name\":\"file.gpml\",\"path\":\"path/to/file.gpml\",\"sha\":\"abc123def456\"}";
     * String sha = JsonParser.parseSHAFromResponse(jsonResponse); // "abc123def456"
     * </pre>
     *
     * @param jsonResponse the raw JSON response string from GitHub's API
     * @return the extracted "sha" value, or {@code null} if not found
     */
    public static String parseSHAFromResponse(String jsonResponse) throws Exception
    {
        String sha = extractValue(jsonResponse, "sha");
        if (sha == null) {
            throw new Exception("Failed to extract SHA from JSON response");
        }
        return sha;
    }

}
