package org.pathvisio.githubplugin.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpUtil
{
    /**
	 * HTTP connection timeout in milliseconds.
	 */
	private static final int CONNECT_TIMEOUT_MS = 10_000;

	/**
	 * HTTP read timeout in milliseconds.
	 */
	private static final int READ_TIMEOUT_MS = 10_000;
    private HttpUtil() {}
    public static HttpURLConnection openAuthenticatedConnection(String endpoint, String method, String token) throws IOException
    {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        return connection;
    }

    public static String readResponseBody (HttpURLConnection connection) throws IOException
    {
       java.io.InputStream stream = connection.getErrorStream();
         if (stream == null) {
              stream = connection.getInputStream();
         }
         byte [] bytes = stream.readAllBytes();
         return new String(bytes, java.nio.StandardCharsets.UTF_8);
    }
}



