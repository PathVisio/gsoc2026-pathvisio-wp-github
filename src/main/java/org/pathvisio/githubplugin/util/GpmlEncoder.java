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

public class GpmlEncoder {
    public static String encodeToBase64(PathwayModel pathwayModel) throws Exception {
        String gpmlString = readAsString(pathwayModel);
        byte[] utf8Bytes = toUtf8Bytes(gpmlString);
        return toBase64(utf8Bytes);
    }
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
    private static byte[] toUtf8Bytes(String gpmlString) 
    {
        return gpmlString.getBytes(StandardCharsets.UTF_8);
    }
    private static String toBase64(byte[] utf8Bytes) 
    {
        return Base64.getEncoder().encodeToString(utf8Bytes);
    }
}
