package com.example.wearapp_ver2;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeasurementService extends Service
        implements SensorRecorder.Listener {

    private static final String TAG = "MeasurementService";
    private static final String CHANNEL_ID = "measurement";
    private static final int NOTIFICATION_ID = 1;

    // サービス再生成時も、旧処理の終了後に次のファイル処理を実行する。
    private static final ExecutorService FILE_IO =
            Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "measurement-files")
            );

    public enum State {
        CHECKING,
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        SAVING,
        SAVED,
        RESETTING,
        ERROR
    }

    private final LocalBinder binder = new LocalBinder();

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private SessionFiles files;
    private SessionFiles.Session session;
    private SensorRecorder recorder;

    private PowerManager.WakeLock wakeLock;

    private State state = State.CHECKING;
    private Runnable stateListener;

    private long startNs;
    private long stopNs = -1L;

    private String detail = "";
    private String pendingMessage;

    private boolean destroyed;

    public final class LocalBinder extends Binder {
        public MeasurementService getService() {
            return MeasurementService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadPending(false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    public void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    public void removeStateListener(Runnable listener) {
        if (stateListener == listener) {
            stateListener = null;
        }
    }

    public State getState() {
        return state;
    }

    public boolean canSave() {
        return (state == State.STOPPED || state == State.ERROR)
                && session != null
                && session.validHeader
                && !session.saved;
    }

    public boolean canReset() {
        return state == State.STOPPED
                || state == State.SAVED
                || state == State.ERROR;
    }

    public boolean hasUnsavedData() {
        return session != null && !session.saved;
    }

    public boolean isIncomplete() {
        return session != null && !session.complete;
    }

    public boolean isClockAdvancing() {
        return stopNs < 0L
                && (state == State.STARTING
                || state == State.RUNNING
                || state == State.STOPPING);
    }

    public long getElapsedTimeNs() {
        if (state == State.IDLE || state == State.CHECKING) {
            return 0L;
        }

        if (isClockAdvancing()) {
            return SystemClock.elapsedRealtimeNanos() - startNs;
        }

        if (stopNs >= startNs) {
            return stopNs - startNs;
        }

        return -1L;
    }

    public String getStatusText() {
        switch (state) {
            case CHECKING:
                return "未保存データを確認中";
            case IDLE:
                return "計測できます";
            case STARTING:
                return "計測準備中";
            case RUNNING:
                return "計測中";
            case STOPPING:
                return "書き込み完了待ち";
            case STOPPED:
                return "未保存";
            case SAVING:
                return "保存中";
            case SAVED:
                return "保存しました";
            case RESETTING:
                return "リセット中";
            case ERROR:
                return detail.isEmpty() ? "記録が中断しました" : detail;
            default:
                return "";
        }
    }

    public String consumeMessage() {
        String message = pendingMessage;
        pendingMessage = null;
        return message;
    }

    public void startMeasurement(
            long requestedStartNs,
            long startWallTimeMs
    ) {
        if (destroyed || state != State.IDLE) {
            return;
        }

        startNs = requestedStartNs;
        stopNs = -1L;
        session = null;
        detail = "";
        pendingMessage = null;
        state = State.STARTING;

        try {
            Notification notification = createNotification();

            ContextCompat.startForegroundService(
                    this,
                    new Intent(this, MeasurementService.class)
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            acquireWakeLock();

            String stem = new SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.ROOT
            ).format(new Date(startWallTimeMs));

            SensorManager manager =
                    getSystemService(SensorManager.class);

            recorder = new SensorRecorder(
                    manager,
                    files,
                    stem,
                    startNs,
                    this
            );

            FILE_IO.execute(recorder);

        } catch (RuntimeException exception) {
            Log.e(TAG, "計測を開始できませんでした", exception);

            recorder = null;
            stopNs = SystemClock.elapsedRealtimeNanos();
            state = State.ERROR;
            detail = "計測を開始できませんでした";

            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        notifyStateChanged();
    }

    public void stopMeasurement() {
        if (destroyed
                || (state != State.STARTING && state != State.RUNNING)) {
            return;
        }

        state = State.STOPPING;
        recorder.requestStop();

        notifyStateChanged();
    }

    public void saveMeasurement() {
        if (destroyed || !canSave()) {
            return;
        }

        SessionFiles.Session target = session;
        State previousState = state;

        state = State.SAVING;
        notifyStateChanged();

        FILE_IO.execute(() -> {
            try {
                files.save(target);

                mainHandler.post(() -> {
                    if (destroyed || session != target) {
                        return;
                    }

                    state = State.SAVED;
                    detail = "";
                    notifyStateChanged();
                });

            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "保存できませんでした", exception);

                mainHandler.post(() -> {
                    if (destroyed || session != target) {
                        return;
                    }

                    state = previousState;
                    pendingMessage = "保存できませんでした";
                    notifyStateChanged();
                });
            }
        });
    }

    public void resetMeasurement() {
        if (destroyed || !canReset()) {
            return;
        }

        state = State.RESETTING;
        pendingMessage = null;

        notifyStateChanged();
        loadPending(true);
    }

    private void loadPending(boolean discardCurrent) {
        SessionFiles.Session target = session;

        FILE_IO.execute(() -> {
            try {
                SessionFiles storage = files;

                if (storage == null) {
                    storage = new SessionFiles(getFilesDir());
                }

                if (discardCurrent && target != null) {
                    storage.discard(target);
                }

                SessionFiles.Session recovered = storage.recover();
                SessionFiles readyStorage = storage;

                mainHandler.post(() -> {
                    if (destroyed) {
                        return;
                    }

                    files = readyStorage;
                    session = recovered;

                    if (recovered == null) {
                        startNs = 0L;
                        stopNs = -1L;
                        state = State.IDLE;
                        detail = "";

                    } else {
                        startNs = recovered.startNs;
                        stopNs = recovered.stopNs;

                        state = recovered.complete
                                ? State.STOPPED
                                : State.ERROR;

                        detail = recovered.problem;
                    }

                    notifyStateChanged();
                });

            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "未保存ファイルの処理に失敗しました", exception);

                mainHandler.post(() -> {
                    if (destroyed) {
                        return;
                    }

                    state = State.ERROR;

                    detail = discardCurrent
                            ? "リセットできませんでした"
                            : "記録ファイルを確認できません";

                    notifyStateChanged();
                });
            }
        });
    }

    @Override
    public void onStarted(SensorRecorder source) {
        if (destroyed || recorder != source) {
            return;
        }

        if (state == State.STARTING) {
            state = State.RUNNING;
            notifyStateChanged();
        }
    }

    @Override
    public void onCaptureStopped(
            SensorRecorder source,
            long capturedStopNs
    ) {
        if (destroyed || recorder != source) {
            return;
        }

        stopNs = capturedStopNs;
        state = State.STOPPING;

        notifyStateChanged();
    }

    @Override
    public void onFinished(
            SensorRecorder source,
            SessionFiles.Session finishedSession,
            String error,
            long finishedStopNs
    ) {
        if (recorder != source) {
            return;
        }

        recorder = null;
        releaseWakeLock();

        if (destroyed) {
            return;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();

        session = finishedSession;
        stopNs = finishedStopNs;

        if (session != null) {
            session.stopNs = finishedStopNs;
        }

        if (error == null && session != null && session.complete) {
            state = State.STOPPED;
            detail = "";

        } else {
            state = State.ERROR;
            detail = error == null ? "記録が中断しました" : error;
        }

        notifyStateChanged();
    }

    private void notifyStateChanged() {
        if (!destroyed && stateListener != null) {
            stateListener.run();
        }
    }

    // 任意の時間で自動解放せず、記録終了後に解放する。
    @SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        PowerManager manager =
                getSystemService(PowerManager.class);

        wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WearApp:Measurement"
        );

        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }

        if (wakeLock.isHeld()) {
            wakeLock.release();
        }

        wakeLock = null;
    }

    private Notification createNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.measurement_channel),
                NotificationManager.IMPORTANCE_LOW
        );

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        manager.createNotificationChannel(channel);

        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                );

        PendingIntent openActivity = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_measurement)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.measurement_running))
                .setContentIntent(openActivity)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stateListener = null;

        if (recorder != null) {
            recorder.abort("サービスが終了したため記録を中断しました");
        } else {
            releaseWakeLock();
        }

        // FILE_IO はプロセスで共有するため、ここでは終了しない。
        super.onDestroy();
    }
}