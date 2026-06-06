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
 * Utility class for encoding GPML pathway models to Base64 format.
 * Provides methods to convert PathwayModel objects to their Base64-encoded string representations
 * and handle HTTP response reading operations.
 */
public class GpmlEncoder {
    
    /**
     * Encodes a PathwayModel to a Base64-encoded string.
     * 
     * This method performs the following steps:
     * 1. Converts the PathwayModel to a GPML XML string representation
     * 2. Encodes the string as UTF-8 bytes
     * 3. Encodes the bytes to a Base64 string
     * 
     * @param pathwayModel the PathwayModel object to be encoded
     * @return a Base64-encoded string representation of the pathway model
     * @throws Exception if an error occurs during conversion or encoding
     */
    public static String encodeToBase64(PathwayModel pathwayModel) throws Exception {
        String gpmlString = readAsString(pathwayModel);
        byte[] utf8Bytes = toUtf8Bytes(gpmlString);
        return toBase64(utf8Bytes);
    }
    
    /**
     * Converts a PathwayModel to its GPML XML string representation.
     * 
     * Serializes the provided PathwayModel object using the GPML2021 format
     * and returns the result as a UTF-8 encoded string.
     * 
     * @param pathwayModel the PathwayModel object to convert
     * @return a GPML XML string representation of the pathway model
     * @throws Exception if a ConverterException occurs during the conversion process
     */
    private static String readAsString(PathwayModel pathwayModel) throws Exception {
       ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            GPMLFormat.GPML2021.writeToXml(pathwayModel, outputStream, false);
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } 
        catch (ConverterException e) {
            throw new Exception("Error converting PathwayModel to GPML string", e);
        }
    }
    
    /**
     * Converts a GPML string to UTF-8 encoded bytes.
     * 
     * Encodes the provided GPML XML string using the UTF-8 character set.
     * 
     * @param gpmlString the GPML XML string to encode
     * @return a byte array containing the UTF-8 encoded representation of the string
     */
    private static byte[] toUtf8Bytes(String gpmlString) 
    {
        return gpmlString.getBytes(StandardCharsets.UTF_8);
    }
    
    /**
     * Encodes a byte array to a Base64 string.
     * 
     * Converts the provided byte array to its Base64 string representation.
     * 
     * @param utf8Bytes the byte array to be encoded
     * @return a Base64-encoded string representation of the byte array
     */
    private static String toBase64(byte[] utf8Bytes) 
    {
        return Base64.getEncoder().encodeToString(utf8Bytes);
    }
    
    /**
     * Reads the response body from an HTTP connection.
     * 
     * Reads all lines from the input stream of the provided HTTP connection,
     * combines them with newline separators, and returns the complete response body.
     * The input stream is automatically closed after reading due to try-with-resources.
     * 
     * @param connection the HttpURLConnection to read the response from
     * @return the complete HTTP response body as a string
     * @throws Exception if an I/O error occurs while reading the response
     */
    private static String readHttpResponse(HttpURLConnection connection) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line).append("\n");
            }
            return responseBuilder.toString();
        }
    }
}
