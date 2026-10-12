package com.example.wearapp_ver2;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class SensorRecorder implements Runnable, SensorEventListener {

    interface Listener {
        void onStarted(SensorRecorder recorder);

        void onCaptureStopped(SensorRecorder recorder, long stopNs);

        void onFinished(
                SensorRecorder recorder,
                SessionFiles.Session session,
                String error,
                long stopNs
        );
    }

    private static final String TAG = "SensorRecorder";

    private static final int QUEUE_CAPACITY = 2048;
    private static final long NO_STOP_REQUEST = -2L;
    private static final long INTERNAL_STOP = -1L;
    private static final long FLUSH_INTERVAL_MS = 1_000L;
    private static final long SYNC_INTERVAL_MS = 5_000L;

    // 本番実験では false にする。停止時の最終集計は常に出力する。
    private static final boolean PERIODIC_QUEUE_LOG_ENABLED = true;
    private static final long QUEUE_LOG_INTERVAL_MS = 1_000L;

    private static final int[] SENSOR_TYPES = {
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR
    };

    private final SensorManager sensorManager;
    private final SessionFiles files;
    private final String stem;
    private final long startNs;
    private final Listener listener;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ArrayBlockingQueue<Sample> queue =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    // 要求の有無と操作時刻を一度の CAS で公開する。
    private final AtomicLong stopRequestNs = new AtomicLong(NO_STOP_REQUEST);

    private final AtomicReference<String> failure =
            new AtomicReference<>();

    private final CountDownLatch captureStopped =
            new CountDownLatch(1);

    private final float[] quaternion = new float[4];

    private volatile Handler sensorHandler;
    private volatile boolean producerDone;
    private volatile long stopNs = -1L;

    // 更新はセンサスレッドのみ。書き込みスレッドはログ出力のために読む。
    private volatile int maxObservedQueueSize;
    private volatile boolean queueOfferAttempted;
    private volatile long queueFullCount;

    private HandlerThread sensorThread;

    // センサスレッドだけで使用する。
    private boolean accepting;

    SensorRecorder(
            SensorManager sensorManager,
            SessionFiles files,
            String stem,
            long startNs,
            Listener listener
    ) {
        this.sensorManager = sensorManager;
        this.files = files;
        this.stem = stem;
        this.startNs = startNs;
        this.listener = listener;
    }

    boolean requestStop(long requestedStopNs) {
        if (requestedStopNs < startNs) {
            throw new IllegalArgumentException("STOP 時刻が START より前です");
        }
        return requestStopAt(requestedStopNs);
    }

    private void requestStop() {
        requestStopAt(INTERNAL_STOP);
    }

    private boolean requestStopAt(long requestedStopNs) {
        if (!stopRequestNs.compareAndSet(NO_STOP_REQUEST, requestedStopNs)) {
            return false;
        }

        Handler handler = sensorHandler;
        if (handler != null) {
            handler.post(this::finishCapture);
        }
        return true;
    }

    void abort(String reason) {
        fail(reason, null);
        requestStop();
    }

    @Override
    public void run() {
        SessionFiles.Session session = null;

        try {
            session = files.create(stem, startNs);

            try (FileOutputStream output =
                         new FileOutputStream(session.temp);

                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(
                                 output,
                                 StandardCharsets.UTF_8
                         ),
                         64 * 1024
                 )) {

                try {
                    writer.write(SessionFiles.HEADER);
                    writer.write('\n');
                    writer.write(Long.toString(startNs));
                    writer.write(",START,,,,\n");

                    writer.flush();
                    output.getFD().sync();

                    session.validHeader = true;

                    startSensorThread();
                    drainQueue(writer, output);

                    // 操作 STOP だけを記録する。異常終了時刻で代用しない。
                    long requestedStopNs = stopRequestNs.get();
                    if (requestedStopNs >= 0L) {
                        writer.write(Long.toString(requestedStopNs));
                        writer.write(",STOP,,,,\n");
                    }

                    writer.flush();
                    output.getFD().sync();

                } catch (IOException | RuntimeException exception) {
                    fail("記録ファイルへの書き込みに失敗しました", exception);
                    requestStop();
                    throw exception;
                }
            }

            if (failure.get() == null) {
                files.complete(session, stopNs);
            }

        } catch (IOException | RuntimeException exception) {
            fail("記録処理を完了できませんでした", exception);

        } finally {
            requestStop();

            if (sensorHandler == null) {
                finishCapture();
            }

            awaitCaptureStopped();
            logQueueStats("final");

            if (sensorThread != null) {
                sensorThread.quitSafely();
            }

            SessionFiles.Session finishedSession = session;
            String error = failure.get();

            if (finishedSession != null && error != null) {
                finishedSession.problem = error;
            }

            long finishedStopNs = stopNs;

            mainHandler.post(() -> listener.onFinished(
                    this,
                    finishedSession,
                    error,
                    finishedStopNs
            ));
        }
    }

    private void drainQueue(
            BufferedWriter writer,
            FileOutputStream output
    ) throws IOException {
        long lastFlushMs = SystemClock.elapsedRealtime();
        long lastSyncMs = lastFlushMs;
        long lastQueueLogMs = lastFlushMs;

        while (!producerDone || !queue.isEmpty()) {
            Sample sample;

            try {
                sample = queue.poll(100L, TimeUnit.MILLISECONDS);

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("記録スレッドが中断されました", exception);
            }

            if (sample != null) {
                sample.writeTo(writer);
            }

            long nowMs = SystemClock.elapsedRealtime();

            if (nowMs - lastSyncMs >= SYNC_INTERVAL_MS) {
                writer.flush();
                output.getFD().sync();

                lastFlushMs = nowMs;
                lastSyncMs = nowMs;

            } else if (nowMs - lastFlushMs >= FLUSH_INTERVAL_MS) {
                writer.flush();
                lastFlushMs = nowMs;
            }

            if (PERIODIC_QUEUE_LOG_ENABLED
                    && nowMs - lastQueueLogMs >= QUEUE_LOG_INTERVAL_MS) {
                logQueueStats("periodic");
                lastQueueLogMs = SystemClock.elapsedRealtime();
            }
        }
    }

    private void logQueueStats(String phase) {
        int current = queue.size();
        long fullCount = queueFullCount;
        int observed = maxObservedQueueSize;
        if (fullCount > 0L) {
            observed = QUEUE_CAPACITY;
        }
        int upper = queueOfferAttempted
                ? Math.min(QUEUE_CAPACITY, observed + 1)
                : observed;

        // 1 producer の offer 直前に観測するため、真の最大は observed..upper。
        // current はログ時の瞬時値。I/O 待ち中は周期ログ自体も遅れる。
        Log.i(TAG, String.format(
                Locale.ROOT,
                "Queue %s startNs=%d current=%d capacity=%d "
                        + "maxObserved=%d maxUpper=%d "
                        + "maxPctObserved=%.3f maxPctUpper=%.3f full=%d",
                phase, startNs, current, QUEUE_CAPACITY,
                observed, upper,
                observed * 100.0 / QUEUE_CAPACITY,
                upper * 100.0 / QUEUE_CAPACITY,
                fullCount
        ));
    }

    private void startSensorThread() {
        sensorThread = new HandlerThread("measurement-sensors");
        sensorThread.start();

        Handler handler = new Handler(sensorThread.getLooper());

        if (!handler.post(() -> registerSensors(handler))) {
            throw new IllegalStateException("センサスレッドを開始できません");
        }

        sensorHandler = handler;

        // Handler を公開する前に要求された STOP も処理する。
        if (stopRequestNs.get() != NO_STOP_REQUEST) {
            handler.post(this::finishCapture);
        }
    }

    private void registerSensors(Handler handler) {
        if (stopRequestNs.get() != NO_STOP_REQUEST) {
            finishCapture();
            return;
        }

        try {
            for (int type : SENSOR_TYPES) {
                Sensor sensor = sensorManager.getDefaultSensor(type);

                if (sensor == null) {
                    throw new IllegalStateException(
                            "必要なセンサがありません。type=" + type
                    );
                }

                boolean registered = sensorManager.registerListener(
                        this,
                        sensor,
                        SensorManager.SENSOR_DELAY_GAME,
                        0,
                        handler
                );

                if (!registered) {
                    throw new IllegalStateException(
                            "センサを登録できません。type=" + type
                    );
                }
            }

            if (stopRequestNs.get() != NO_STOP_REQUEST) {
                finishCapture();
                return;
            }

            accepting = true;

            mainHandler.post(() -> listener.onStarted(this));

        } catch (RuntimeException exception) {
            fail("センサ取得を開始できませんでした", exception);
            finishCapture();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!accepting || event.timestamp < startNs) {
            return;
        }

        try {
            if (event.values.length < 3) {
                throw new IllegalStateException("センサ値が不足しています");
            }

            String tag;
            boolean rotation;

            switch (event.sensor.getType()) {
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    tag = "Lacc";
                    rotation = false;
                    break;

                case Sensor.TYPE_GYROSCOPE:
                    tag = "Gyro";
                    rotation = false;
                    break;

                case Sensor.TYPE_GAME_ROTATION_VECTOR:
                    tag = "GameRo";
                    rotation = true;
                    break;

                case Sensor.TYPE_ROTATION_VECTOR:
                    tag = "Ro";
                    rotation = true;
                    break;

                default:
                    return;
            }

            Sample sample;

            if (rotation) {
                SensorManager.getQuaternionFromVector(
                        quaternion,
                        event.values
                );

                sample = new Sample(
                        event.timestamp,
                        tag,
                        quaternion[1],
                        quaternion[2],
                        quaternion[3],
                        quaternion[0],
                        true
                );

            } else {
                sample = new Sample(
                        event.timestamp,
                        tag,
                        event.values[0],
                        event.values[1],
                        event.values[2],
                        0.0f,
                        false
                );
            }

            // offer 後だけの size() では、消費との競合でピークを見逃す。
            int sizeBeforeOffer = queue.size();
            if (sizeBeforeOffer > maxObservedQueueSize) {
                maxObservedQueueSize = sizeBeforeOffer;
            }
            if (!queueOfferAttempted) {
                queueOfferAttempted = true;
            }

            if (!queue.offer(sample)) {
                maxObservedQueueSize = QUEUE_CAPACITY;
                queueFullCount++;
                fail("書き込みキューが満杯になったため停止しました", null);
                finishCapture();
            }

        } catch (RuntimeException exception) {
            fail("センサデータの処理に失敗しました", exception);
            finishCapture();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 生データの記録条件には使用しない。
    }

    private void finishCapture() {
        if (producerDone) {
            return;
        }

        stopRequestNs.compareAndSet(NO_STOP_REQUEST, INTERNAL_STOP);
        long requestedStopNs = stopRequestNs.get();
        // 操作時刻と内部終了時刻を区別し、操作時刻を上書きしない。
        stopNs = requestedStopNs >= 0L
                ? requestedStopNs
                : SystemClock.elapsedRealtimeNanos();

        accepting = false;

        try {
            sensorManager.unregisterListener(this);

        } catch (RuntimeException exception) {
            fail("センサの登録解除に失敗しました", exception);

        } finally {
            long capturedStopNs = stopNs;

            // 書き込み完了通知より先に停止時刻を通知する。
            mainHandler.post(() -> listener.onCaptureStopped(
                    this,
                    capturedStopNs
            ));

            producerDone = true;
            captureStopped.countDown();
        }
    }

    private void awaitCaptureStopped() {
        boolean interrupted = false;

        for (;;) {
            try {
                captureStopped.await();
                break;

            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void fail(String message, Throwable exception) {
        if (failure.compareAndSet(null, message)) {
            if (exception == null) {
                Log.e(TAG, message);
            } else {
                Log.e(TAG, message, exception);
            }
        }
    }

    private static final class Sample {
        private final long timestamp;
        private final String tag;

        private final float x;
        private final float y;
        private final float z;
        private final float w;

        private final boolean hasW;

        Sample(
                long timestamp,
                String tag,
                float x,
                float y,
                float z,
                float w,
                boolean hasW
        ) {
            this.timestamp = timestamp;
            this.tag = tag;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.hasW = hasW;
        }

        void writeTo(BufferedWriter writer) throws IOException {
            writer.write(Long.toString(timestamp));
            writer.write(',');
            writer.write(tag);
            writer.write(',');
            writer.write(Float.toString(x));
            writer.write(',');
            writer.write(Float.toString(y));
            writer.write(',');
            writer.write(Float.toString(z));
            writer.write(',');

            if (hasW) {
                writer.write(Float.toString(w));
            }

            writer.write('\n');
        }
    }
}