package org.pathvisio.githubplugin.util;
import java.io.StringWriter;           
import java.io.BufferedReader;         
import java.io.InputStreamReader;      
import java.net.HttpURLConnection;     
import java.net.URL;                   
import java.nio.charset.StandardCharsets; 
import java.util.Base64;
import org.pathvisio.githubplugin.util.JsonParser;

public class GpmlEncoder {
    public static String encodeToBase64(Pathway pathway) throws Exception {
        // TODO: Implement pipeline calling M1, M2, and M3
        throw new UnsupportedOperationException("Not yet implemented");
    }
    private static String readAsString(Pathway pathway) throws Exception {
        // TODO: Utilize GPMLFormat to write to StringWriter
        throw new UnsupportedOperationException("Not yet implemented");
    }
    private static byte[] toUtf8Bytes(String gpmlString) {
        // TODO: Convert string using StandardCharsets.UTF_8
        throw new UnsupportedOperationException("Not yet implemented");
    }
    private static String toBase64(byte[] utf8Bytes) {
        // TODO: Encode bytes using java.util.Base64
        throw new UnsupportedOperationException("Not yet implemented");
    }
    public static String fetchExistingFileSha(String pathInRepo, String accessToken) throws Exception {
        // TODO: Setup HttpURLConnection GET request and parse JSON for "sha"
        throw new UnsupportedOperationException("Not yet implemented");
    }
    private static String readHttpResponse(HttpURLConnection conn) throws Exception {
        // TODO: Read InputStreamReader wrapped in BufferedReader
        throw new UnsupportedOperationException("Not yet implemented");
    }

}
