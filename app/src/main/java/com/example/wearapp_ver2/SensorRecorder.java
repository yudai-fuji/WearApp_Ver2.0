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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long FLUSH_INTERVAL_MS = 1_000L;
    private static final long SYNC_INTERVAL_MS = 5_000L;

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

    private final AtomicBoolean stopRequested = new AtomicBoolean();

    private final AtomicReference<String> failure =
            new AtomicReference<>();

    private final CountDownLatch captureStopped =
            new CountDownLatch(1);

    private final float[] quaternion = new float[4];

    private volatile Handler sensorHandler;
    private volatile boolean producerDone;
    private volatile long stopNs = -1L;

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

    void requestStop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }

        Handler handler = sensorHandler;

        if (handler != null) {
            handler.post(this::finishCapture);
        }
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
        }
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
        if (stopRequested.get()) {
            handler.post(this::finishCapture);
        }
    }

    private void registerSensors(Handler handler) {
        if (stopRequested.get()) {
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

            if (stopRequested.get()) {
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

            if (!queue.offer(sample)) {
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

        stopNs = SystemClock.elapsedRealtimeNanos();

        accepting = false;
        stopRequested.set(true);

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