package tech.bogomolov.incomingsmsgateway;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import tech.bogomolov.incomingsmsgateway.SSLSocketFactory.TLSSocketFactory;

public class Request {

    private final String payload;
    private boolean ignoreSsl = false;
    private boolean useChunkedMode = true;
    private String error = null;
    private int responseCode = -1;

    private HttpURLConnection connection;

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_RETRY = "error_retry";

    public Request(String urlString, String payload) {
        this.payload = payload;

        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Log.e("SmsGateway", "malformed url error: " + urlString);
            this.error = RESULT_ERROR;
            return;
        }

        try {
            this.connection = (HttpURLConnection) url.openConnection();
        } catch (IOException e) {
            Log.e("SmsGateway", "open connection error: " + e);
            this.error = RESULT_ERROR;
            return;
        }

        this.connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    }

    public void setJsonHeaders(String headers) {
        JSONObject headersObj;
        try {
            headersObj = new JSONObject(headers);
            Iterator<String> keys = headersObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (headersObj.get(key) instanceof JSONObject) {
                    Log.e("SmsGateway", "only string supported in json");
                    continue;
                }

                this.connection.setRequestProperty(key, (String) headersObj.get(key));
            }
        } catch (JSONException e) {
            Log.e("SmsGateway", "headers error: " + e);
            this.error = RESULT_ERROR;
        }
    }

    public static String convertByteToHexadecimal(@NonNull byte[] byteArray) {
        StringBuilder hex = new StringBuilder();
        for (byte i : byteArray) {
            hex.append(String.format("%02x", i));
        }
        return hex.toString();
    }

    public static String computeHmacSha256Hex(@NonNull String secret, @NonNull String body)
            throws NoSuchAlgorithmException, InvalidKeyException {
        String algorithm = "HmacSHA256";
        SecretKeySpec secretKeySpec =
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm);
        Mac mac = Mac.getInstance(algorithm);
        mac.init(secretKeySpec);
        return convertByteToHexadecimal(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    public void setSignatureHeader(@NonNull String secret, @NonNull String body) {
        try {
            this.connection.setRequestProperty("X-Signature", computeHmacSha256Hex(secret, body));
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
            // IllegalArgumentException covers an empty secret, which SecretKeySpec
            // rejects — never let a bad signing config crash the delivery path.
            Log.e("SmsGateway", "hmac signature error: " + e);
        }
    }


    public void setIgnoreSsl(boolean ignoreSsl) {
        this.ignoreSsl = ignoreSsl;
    }

    public void setUseChunkedMode(boolean useChunkedMode) {
        this.useChunkedMode = useChunkedMode;
    }

    // HTTP status of the last execute(), or -1 if the request never got a response
    // (malformed URL, connection failure, or not yet executed).
    public int getResponseCode() {
        return this.responseCode;
    }

    @SuppressLint({"AllowAllHostnameVerifier"})
    public String execute() {
        if (this.error != null) {
            return this.error;
        }

        String result = RESULT_SUCCESS;

        try {
            if (this.connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) this.connection).setSSLSocketFactory(
                        new TLSSocketFactory(this.ignoreSsl)
                );

                if (this.ignoreSsl) {
                    ((HttpsURLConnection) this.connection).setHostnameVerifier(new AllowAllHostnameVerifier());
                }
            }

            this.connection.setDoOutput(true);
            if (this.useChunkedMode) {
                this.connection.setChunkedStreamingMode(0);
            } else {
                // Content-Length must be the UTF-8 byte count, not the char count, or a
                // payload with multi-byte characters gets truncated server-side.
                this.connection.setFixedLengthStreamingMode(
                        this.payload.getBytes(StandardCharsets.UTF_8).length);
            }

            OutputStream out = new BufferedOutputStream(this.connection.getOutputStream());

            BufferedWriter writer;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            } else {
                writer = new BufferedWriter(new OutputStreamWriter(out));
            }

            writer.write(this.payload);
            writer.flush();
            writer.close();
            out.close();

            // getResponseCode() does not throw on 4xx/5xx, so read it first.
            // The actual error body (if any) is exposed via getErrorStream(), not
            // getInputStream() — reading getInputStream() on a non-2xx response throws.
            this.responseCode = this.connection.getResponseCode();

            boolean isSuccess = this.responseCode >= 200 && this.responseCode < 300;
            InputStream responseStream =
                    isSuccess ? this.connection.getInputStream() : this.connection.getErrorStream();
            if (responseStream != null) {
                new BufferedInputStream(responseStream).close();
            }

            if (!isSuccess) {
                Log.e("SmsGateway", "response code: " + this.responseCode + " for " + this.connection.getURL());
                result = RESULT_RETRY;
            }
        } catch (NoSuchAlgorithmException e) {
            Log.e("SmsGateway", "ssl algorithm error: " + e);
            result = RESULT_ERROR;
        } catch (KeyManagementException e) {
            Log.e("SmsGateway", "ssl factory error: " + e);
            result = RESULT_ERROR;
        } catch (IOException e) {
            Log.e("SmsGateway", "io error " + e);
            result = RESULT_RETRY;
        } finally {
            if (this.connection != null) {
                this.connection.disconnect();
            }
        }

        return result;
    }
}
