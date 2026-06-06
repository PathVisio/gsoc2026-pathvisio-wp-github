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

import java.io.StringWriter;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.pathvisio.githubplugin.util.JsonParser;
import org.pathvisio.libgpml.model.PathwayModel;
import org.pathvisio.libgpml.model.GPMLFormat;
import org.pathvisio.libgpml.io.ConverterException;

/**
 * Utility class for encoding GPML pathway models to Base64-encoded strings.
 *
 * <p>This encoder provides a convenient interface for converting {@link PathwayModel}
 * objects into Base64-encoded GPML XML strings. The encoding process involves:</p>
 * <ol>
 * <li>Converting the PathwayModel to a GPML 2021 XML string</li>
 * <li>Encoding the XML string to UTF-8 bytes</li>
 * <li>Encoding the UTF-8 bytes to Base64</li>
 * </ol>
 *
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * PathwayModel model = loadPathwayModel(...);
 * String base64Encoded = GpmlEncoder.encodeToBase64(model);
 * // base64Encoded can now be transmitted over HTTP or stored as a string
 * </pre>
 *
 * <p><strong>Exception Handling:</strong> This class converts any {@link ConverterException}
 * thrown during GPML conversion to a {@link Exception} with a descriptive error message.
 * The original exception is preserved as the cause for debugging purposes.</p>
 *
 * <p>This class is intentionally kept separate from other encoding/decoding logic
 * so that GPML encoding can be tested independently and reused across different
 * components of the GitHub plugin.</p>
 *
 * <p>This class cannot be instantiated — all methods are static.</p>
 *
 * @author Snehashree Prusty
 * @version 1.0
 * @see PathwayModel
 * @see GPMLFormat
 */
public class GpmlEncoder
{
    /**
     * Private constructor — prevents instantiation.
     * All methods are static. This class is a utility namespace only.
     */
    private GpmlEncoder()
    {
        throw new UnsupportedOperationException("GpmlEncoder is a utility class and cannot be instantiated.");
    }

    /**
     * Encodes a PathwayModel to a Base64-encoded GPML XML string.
     *
     * <p>This is the primary public method that orchestrates the complete encoding pipeline:
     * PathwayModel → GPML XML string → UTF-8 bytes → Base64 string.</p>
     *
     * <p><strong>Encoding Process:</strong></p>
     * <ol>
     * <li>Converts the PathwayModel to GPML 2021 XML format using
     *     {@link GPMLFormat#writeToXml(PathwayModel, java.io.OutputStream, boolean)}</li>
     * <li>Encodes the resulting XML string to UTF-8 bytes</li>
     * <li>Encodes the UTF-8 bytes to Base64 using Java's {@link java.util.Base64} encoder</li>
     * </ol>
     *
     * <p><strong>Example:</strong></p>
     * <pre>
     * try {
     *     String base64 = GpmlEncoder.encodeToBase64(pathwayModel);
     *     System.out.println("Encoded: " + base64);
     * } catch (Exception e) {
     *     System.err.println("Failed to encode pathway: " + e.getMessage());
     *     e.printStackTrace();
     * }
     * </pre>
     *
     * @param pathwayModel the PathwayModel to encode — must not be {@code null}
     * @return a Base64-encoded string representing the GPML XML of the pathway
     * @throws Exception if the PathwayModel cannot be converted to GPML format
     *
     * @see #readAsString(PathwayModel)
     * @see #toUtf8Bytes(String)
     * @see #toBase64(byte[])
     */
    public static String encodeToBase64(PathwayModel pathwayModel) throws Exception
    {
        String gpmlString = readAsString(pathwayModel);
        byte[] utf8Bytes = toUtf8Bytes(gpmlString);
        return toBase64(utf8Bytes);
    }

    /**
     * Converts a PathwayModel to its GPML 2021 XML string representation.
     *
     * <p>This private helper method uses the {@link GPMLFormat#GPML2021} converter
     * to serialize the PathwayModel to XML. The resulting string is UTF-8 encoded.</p>
     *
     * <p><strong>Error Handling:</strong> Any {@link ConverterException} thrown during
     * conversion is wrapped in a generic {@link Exception} with a descriptive message,
     * while preserving the original exception as the cause.</p>
     *
     * @param pathwayModel the PathwayModel to convert — must not be {@code null}
     * @return the GPML 2021 XML representation as a UTF-8 string
     * @throws Exception if the conversion fails (wraps any ConverterException)
     */
    private static String readAsString(PathwayModel pathwayModel) throws Exception
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try
        {
            GPMLFormat.GPML2021.writeToXml(pathwayModel, outputStream, false);
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
        catch (ConverterException e)
        {
            throw new Exception("Error converting PathwayModel to GPML string", e);
        }
    }

    /**
     * Encodes a string to UTF-8 bytes.
     *
     * <p>This private helper method converts the given string using the UTF-8 charset,
     * which is the standard encoding for XML and JSON data.</p>
     *
     * @param gpmlString the string to encode — must not be {@code null}
     * @return a byte array containing the UTF-8 encoding of the string
     */
    private static byte[] toUtf8Bytes(String gpmlString)
    {
        return gpmlString.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes a byte array to a Base64-encoded string.
     *
     * <p>This private helper method uses Java's {@link java.util.Base64#getEncoder()}
     * to perform standard Base64 encoding, which is suitable for transmission over
     * HTTP and other text-based protocols.</p>
     *
     * @param utf8Bytes the byte array to encode — must not be {@code null}
     * @return the Base64-encoded string representation of the input bytes
     */
    private static String toBase64(byte[] utf8Bytes)
    {
        return Base64.getEncoder().encodeToString(utf8Bytes);
    }
}
