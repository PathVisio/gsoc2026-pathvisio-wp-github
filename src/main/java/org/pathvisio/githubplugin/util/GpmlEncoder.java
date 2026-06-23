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

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.pathvisio.libgpml.io.ConverterException;
import org.pathvisio.libgpml.model.GPMLFormat;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.githubplugin.util.HttpUtil;

/**
 * An Utility class for encoding GPML pathway models to Base64 format and managing HTTP responses.
 * 
 * This class provides utility methods to serialize PathwayModel objects to GPML XML format,
 * encode them as UTF-8, and convert them to Base64 strings for storage or transmission.
 * It also handles HTTP response reading operations for GitHub API interactions.
 * 
 * <p><strong>Key Responsibilities:</strong></p>
 * <ul>
 * <li>Convert PathwayModel objects to GPML 2013a XML format</li>
 * <li>Encode GPML strings to UTF-8 byte arrays</li>
 * <li>Encode byte arrays to Base64 strings for safe storage/transmission</li>
 * <li>Retrieve and parse existing GPML file SHA values from GitHub API</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * PathwayModel model = // ... obtain model from application
 * try {
 *     String base64EncodedGpml = GpmlEncoder.encodeToBase64(model);
 *     // Use base64EncodedGpml for GitHub API requests
 * } catch (Exception e) {
 *     System.err.println("Failed to encode pathway model: " + e.getMessage());
 * }
 * </pre>
 * 
 * <p><strong>Integration with GitHub API:</strong></p>
 * <p>The {@link #getExistingGpmlSHA(String, String)} method can be used to retrieve
 * SHA values of existing GPML files on GitHub, which is necessary for updating files
 * via the GitHub REST API.</p>
 * 
 * @author Snehashree Prusty
 * @version 1.0
 * @see PathwayModel
 * @see GPMLFormat
 * @see JsonParser
 */
public class GpmlEncoder {
    
    /**
     * Encodes a PathwayModel to a Base64-encoded string.
     * 
     * This method performs the following steps:
	 * <ol>
	 * <li>Converts the PathwayModel to a GPML XML string representation</li>
	 * <li>Encodes the string as UTF-8 bytes</li>
	 * <li>Encodes the bytes to a Base64 string</li>
	 * </ol>
	 * 
	 * <p>The resulting Base64 string is suitable for use in GitHub API requests,
	 * as GitHub's "Create or update file contents" endpoint expects file content
	 * to be Base64-encoded.</p>
	 * 
	 * @param pathwayModel the PathwayModel object to be encoded
	 * @return a Base64-encoded string representation of the pathway model
	 * @throws Exception if an error occurs during conversion to GPML or encoding,
	 *                   including ConverterException from the GPML serialization process
	 * @see #readAsString(PathwayModel)
	 * @see #toUtf8Bytes(String)
	 * @see #toBase64(byte[])
	 */
	public static String encodeToBase64(PathwayModel pathwayModel) throws Exception {
		String gpmlString = readAsString(pathwayModel);
		byte[] utf8Bytes = toUtf8Bytes(gpmlString);
		return toBase64(utf8Bytes);
	}

	/**
	 * Converts a PathwayModel to its GPML XML string representation.
	 * 
	 * Serializes the provided PathwayModel object using the GPML 2013a format
	 * and returns the result as a UTF-8 encoded string. This intermediate format
	 * is required before Base64 encoding for use with GitHub API requests.
	 * 
	 * @param pathwayModel the PathwayModel object to convert
	 * @return a GPML XML string representation of the pathway model
	 * @throws Exception if a ConverterException occurs during the conversion process,
	 *                   wrapped with a descriptive error message
	 */
	private static String readAsString(PathwayModel pathwayModel) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			GPMLFormat.GPML2013a.writeToXml(pathwayModel, outputStream, false);
			return outputStream.toString(StandardCharsets.UTF_8.name());
		} catch (ConverterException e) {
			throw new Exception("Error converting PathwayModel to GPML string", e);
		}
	}
	/**
	 * Converts a GPML string to UTF-8 encoded bytes.
	 * 
	 * Encodes the provided GPML XML string using the UTF-8 character set.
	 * This is an intermediate step before Base64 encoding.
	 * 
	 * @param gpmlString the GPML XML string to encode
	 * @return a byte array containing the UTF-8 encoded representation of the string
	 */
	private static byte[] toUtf8Bytes(String gpmlString) {
		return gpmlString.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Encodes a byte array to a Base64 string.
	 * 
	 * Converts the provided byte array to its Base64 string representation.
	 * The resulting string is safe for use in JSON payloads, URLs, and GitHub API requests.
	 * 
	 * @param utf8Bytes the byte array to be encoded
	 * @return a Base64-encoded string representation of the byte array
	 */
	private static String toBase64(byte[] utf8Bytes) {
		return Base64.getEncoder().encodeToString(utf8Bytes);
	}

	/**
	 * Retrieves the SHA value of an existing GPML file from GitHub.
	 * 
	 * Makes an authenticated GET request to the GitHub REST API to retrieve metadata
	 * about an existing GPML file. The SHA value is essential for updating files via
	 * the GitHub API, as it ensures the update operation is atomic and prevents
	 * concurrent modification conflicts.
	 * 
	 * <p><strong>Authentication:</strong> The provided access token must have sufficient
	 * permissions to read repository contents. Typically, this requires the "repo" or
	 * "public_repo" scope depending on the repository's visibility.</p>
	 * 
	 * <p><strong>API Request Details:</strong></p>
	 * <ul>
	 * <li>HTTP Method: GET</li>
	 * <li>Request Headers: Authorization token, GitHub API version (2026-03-10)</li>
	 * <li>Expected Response: JSON containing file metadata including the SHA field</li>
	 * </ul>
	 * 
	 * @param apiURL the full GitHub API URL for the file contents endpoint
	 *               (e.g., https://api.github.com/repos/owner/repo/contents/path/to/file.gpml)
	 * @param accessToken the GitHub authentication token with appropriate permissions
	 * @return the SHA value of the existing GPML file, or {@code null} if the
     *         file does not yet exist in the repository (HTTP 404). A null return
     *         signals to the caller that this is a new pathway (create flow),
     *         not an update to an existing one.
     * @throws Exception if the HTTP request fails or returns an unexpected
     *         status code other than 200 or 404
     *                   or the response cannot be parsed for the SHA value
	 * @see JsonParser#parseSHAFromResponse(String)
	 */
public static String getExistingGpmlSHA(String apiURL, String accessToken) throws Exception {
    // Uses HttpUtil.openAuthenticatedConnection() — consistent with
    // GitHubForkService and GitHubBranchService, and avoids direct
    // new URL() construction which broke the Mockito URL-count test.
    HttpURLConnection connection = 
        HttpUtil.openAuthenticatedConnection(apiURL, "GET", accessToken);

    int responseCode = connection.getResponseCode();

    if (responseCode == HttpURLConnection.HTTP_OK) {
        // File exists — parse and return its SHA for the update flow
        String responseBody = HttpUtil.readResponseBody(connection);
        connection.disconnect();
        return JsonParser.parseSHAFromResponse(responseBody);
    } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
        // 404 means file doesn't exist yet — this is a new pathway,
        // not an error. Return null so caller knows to create, not update.
        connection.disconnect();
        return null;
    } else {
        connection.disconnect();
        throw new Exception(
            "Failed to retrieve existing GPML SHA. HTTP response code: " + responseCode);
    }
}
}
