package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

public class RequestWorker extends Worker {

    public final static String DATA_URL = "URL";
    public final static String DATA_TEXT = "TEXT";
    public final static String DATA_HEADERS = "HEADERS";
    public final static String DATA_IGNORE_SSL = "IGNORE_SSL";
    public final static String DATA_MAX_RETRIES = "MAX_RETRIES";
    public final static String DATA_CHUNKED_MODE = "CHUNKED_MODE";
    public final static String DATA_SIGN_HMAC_SHA256 = "SIGN_HMAC_SHA256";
    public final static String DATA_SIGN_HMAC_SHA256_SECRET = "SIGN_HMAC_SHA256_SECRET";
    public final static String DATA_STORE_FAILED = "STORE_FAILED";
    public final static String DATA_LOCAL_MODE = "LOCAL_MODE";

    public RequestWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Enqueues a delivery with the standard "wait for network + exponential
     * backoff" policy. Shared by the live SMS path ({@link SmsBroadcastReceiver})
     * and the manual retry path ({@link FailedMessage#retryAll}).
     */
    public static void enqueue(Context context, Data data) {
        // "Local network mode" (issue #83): NetworkType.CONNECTED requires a
        // *validated* internet connection, so forwarding to a LAN endpoint on a
        // Wi-Fi without upstream internet never fires. When the config opts into
        // local mode we drop the constraint (NOT_REQUIRED) so the request runs as
        // soon as it is enqueued instead of waiting for internet that never comes.
        boolean localMode = data.getBoolean(DATA_LOCAL_MODE, false);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(localMode ? NetworkType.NOT_REQUIRED : NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest workRequest =
                new OneTimeWorkRequest.Builder(RequestWorker.class)
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS
                        )
                        .setInputData(data)
                        .build();

        WorkManager.getInstance(context).enqueue(workRequest);
    }

    @NonNull
    @Override
    public Result doWork() {
        int maxRetries = getInputData().getInt(DATA_MAX_RETRIES, 10);
        boolean storeFailed = getInputData().getBoolean(DATA_STORE_FAILED, false);

        if (getRunAttemptCount() > maxRetries) {
            return fail(storeFailed);
        }

        String url = getInputData().getString(DATA_URL);
        String text = getInputData().getString(DATA_TEXT);
        String headers = getInputData().getString(DATA_HEADERS);
        boolean ignoreSsl = getInputData().getBoolean(DATA_IGNORE_SSL, false);
        boolean useChunkedMode = getInputData().getBoolean(DATA_CHUNKED_MODE, true);
        boolean signHmacSha256 = getInputData().getBoolean(DATA_SIGN_HMAC_SHA256, false);
        String signHmacSha256Secret = getInputData().getString(DATA_SIGN_HMAC_SHA256_SECRET);

        Request request = new Request(url, text);
        request.setJsonHeaders(headers);
        // A null/empty secret can't be signed with (and would throw, which makes
        // WorkManager fail the job *without* running the store-failed path). Send
        // unsigned instead: the endpoint's auth rejection stays visible in the
        // syslog via the logged response code.
        if (signHmacSha256 && signHmacSha256Secret != null && !signHmacSha256Secret.isEmpty()) {
            request.setSignatureHeader(signHmacSha256Secret, text);
        } else if (signHmacSha256) {
            Log.e("RequestWorker", "HMAC signing enabled but no secret stored; sending unsigned");
        }

        request.setIgnoreSsl(ignoreSsl);
        request.setUseChunkedMode(useChunkedMode);

        String result = request.execute();

        if (result.equals(Request.RESULT_RETRY)) {
            return Result.retry();
        }

        if (result.equals(Request.RESULT_ERROR)) {
            return fail(storeFailed);
        }

        return Result.success();
    }

    // Permanent failure: optionally persist the payload for manual retry, then
    // report failure so WorkManager stops retrying.
    private Result fail(boolean storeFailed) {
        if (storeFailed) {
            FailedMessage.save(getApplicationContext(), getInputData());
        }
        return Result.failure();
    }
}
