package org.pathvisio.githubplugin;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingWorker;
import java.io.IOException;
import java.util.List;
import org.pathvisio.githubplugin.util.TokenManager;

public class GitHubAuthService {
private static final String CLIENT_ID = "";
private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
private static final String GITHUB_API_VERSION = "2022-11-28";
private static final int CONNECT_TIMEOUT_MS = 10_000;
private static final int READ_TIMEOUT_MS = 10_000;

private String deviceCode;
private int interval;
private SwingWorker<String, String> pollingWorker;

//inner device code response class to hold the response from GitHub
static class DeviceCodeResponse {
    private String deviceCode;
    private String userCode;
    private String verificationUri;
    private int expiresIn;
    private int interval;

    public DeviceCodeResponse(String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.verificationUri = verificationUri;
        this.expiresIn = expiresIn;
        this.interval = interval;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public String getVerificationUri() {
        return verificationUri;
    }

    public int getExpiresIn() {
        return expiresIn;
    }

    public int getInterval() {
        return interval;
    }
}
//authcallback interface to communicate back to the UI
public interface AuthCallback {
    void onUserCodeReceived(String userCode, int expiresIn);
    void onStatusUpdate(String message);
    void onSuccess(String accessToken);
    void onFailure(String errorMessage);
}

//extraction of json parsing to a separate method for better readability
private String extractJsonValue(String json, String key) {
    String searchKey = "\"" + key + "\":";
    int keyIndex = json.indexOf(searchKey);
    if (keyIndex == -1) return null;

    int valueStart = keyIndex+searchKey.length();
     // skip whitespace between colon and value
    while (valueStart < json.length() && 
           Character.isWhitespace(json.charAt(valueStart))) {
        valueStart++;
    }
    if (valueStart >= json.length()) return null;

    if (json.charAt(valueStart)=='"') 
    {
    valueStart++;
    int valueEnd = json.indexOf('"', valueStart);
    if (valueEnd == -1) return null;
    return json.substring(valueStart, valueEnd);
    }
    else
    {
    int valueEnd = json.indexOf(",", valueStart);
    if (valueEnd == -1) valueEnd = json.indexOf("}", valueStart);
    if (valueEnd == -1) return null;
    return json.substring(valueStart, valueEnd).trim();
    }        
}

//check for valid token
private boolean isTokenValid(String token) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.github.com/user");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        int responseCode = conn.getResponseCode();
       return responseCode == HttpURLConnection.HTTP_OK;
    } 
    catch (Exception e) {
        return false; }
        finally {
        if (conn != null) {
            conn.disconnect();
        } 
        }
}
private DeviceCodeResponse requestDeviceCodes() throws IOException {
    HttpURLConnection conn = null;
    try {
        URL url = new URL(DEVICE_CODE_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        String requestBody = "client_id=" + CLIENT_ID +"&scope=repo";

        //try with resources to ensure stream is closed properly
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }
        int statusCode = conn.getResponseCode();
        if (statusCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to request device code: HTTP " + statusCode);
        }
        //read response body full
          StringBuilder response = new StringBuilder();
           try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) 
        {
            String line;
            while ((line = reader.readLine()) != null) 
            {
                response.append(line);
            }
        }
        String json = response.toString();
        String deviceCode = extractJsonValue(json, "device_code");
        String userCode = extractJsonValue(json, "user_code");
        String verificationUri = extractJsonValue(json, "verification_uri");
        String intervalStr = extractJsonValue(json, "interval");
        String expiresInStr = extractJsonValue(json, "expires_in");
    
        if (deviceCode == null || userCode == null || verificationUri == null || intervalStr == null || expiresInStr == null) {
            throw new IOException("Invalid response from GitHub: missing required fields");
        }
        int interval = Integer.parseInt(intervalStr);
        int expiresIn = Integer.parseInt(expiresInStr);
        return new DeviceCodeResponse(deviceCode, userCode, verificationUri, expiresIn, interval);
    } 
     finally {
        if (conn != null) {
            conn.disconnect();
        }
    }
}

private String pollForAccessToken() throws IOException 
{
    HttpURLConnection conn = null;
    try 
    {
        URL url = new URL(ACCESS_TOKEN_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        String requestBody = "client_id=" + CLIENT_ID
            + "&device_code=" + deviceCode
            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

        try (OutputStream os = conn.getOutputStream()) 
        {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }
         int statusCode = conn.getResponseCode();
        if (statusCode != HttpURLConnection.HTTP_OK)
        {
            throw new IOException("GitHub access token poll failed: HTTP " + statusCode);
        }
        
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
        }

        String json = response.toString();
        String accessToken = extractJsonValue(json, "access_token");
        String error = extractJsonValue(json, "error");

         if (accessToken != null)
        {
            return accessToken;
        }
         else if ("authorization_pending".equals(error))
        {
            return null;
        }
        // GitHub is being hit too fast, RFC 8628 requires adding 5 seconds interval
        else if ("slow_down".equals(error))
        {
            interval += 5;
            return null;
        }
        //device code window closed, user did not enter the code in time
        else if ("expired_token".equals(error))
        {
            throw new IOException(
                "Authentication code expired. Please sign in again.");
        }
       //case where user saw the code and explicitly clicked deny
        else if ("access_denied".equals(error))
        {
            throw new IOException(
                "Access denied. User rejected the authorization request.");
        }
        else
        {
            return null;
        }
    } 
    finally 
    {
        if (conn != null) conn.disconnect();
    }
}

private SwingWorker<String, String> createPollingWorker(AuthCallback callback, int expiresIn)
{
    // method to build the polling worker but does NOT execute it, caller calls execute(). runs on EDT, UI access allowed.
    // TODO: implement in next session
    return null;
}

private void beginDeviceAuthFlow(AuthCallback callback)
{
    //method to start the device auth flow, runs on EDT, UI access allowed
    // TODO: implement in next session
}

public void startAuthentication(AuthCallback callback) 
{
    String existingToken = TokenManager.getToken();
    if (existingToken != null) 
    {
        //needs to be validated with GitHub API, must be done in background thread
        SwingWorker<Boolean, Void> validationWorker = new SwingWorker<Boolean, Void>() {

            @Override
            protected Boolean doInBackground() throws Exception {
                return isTokenValid(existingToken);
            }
            @Override //runs on EDT
            protected void done() {
                try {
                    if(get()) 
                    callback.onSuccess(existingToken);
                    else {
                        TokenManager.clearToken();
                        beginDeviceAuthFlow(callback);
                    }
                } catch (Exception e) {
                    // validation failed, start device flow, might be network error, treat as invalid token
                    TokenManager.clearToken();
                    beginDeviceAuthFlow(callback);
                }
            }
        };
        validationWorker.execute();
    }
    else 
    {
    beginDeviceAuthFlow(callback);
    }
}
}
