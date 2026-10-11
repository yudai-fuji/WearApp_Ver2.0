package com.example.wearapp_ver2;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final long DISPLAY_INTERVAL_MS = 50L;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 100;

    private TextView stopwatchText;
    private TextView statusText;

    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private Button resetButton;

    private MeasurementService measurementService;
    private boolean isBound;

    private final Handler displayHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable stateListener = this::renderState;

    private final Runnable displayTask = new Runnable() {
        @Override
        public void run() {
            if (measurementService == null
                    || !measurementService.isClockAdvancing()) {
                return;
            }

            updateStopwatchText();
            displayHandler.postDelayed(this, DISPLAY_INTERVAL_MS);
        }
    };

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {

                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder service
                ) {
                    if (!isBound) {
                        return;
                    }

                    MeasurementService.LocalBinder binder =
                            (MeasurementService.LocalBinder) service;

                    measurementService = binder.getService();
                    measurementService.setStateListener(stateListener);

                    renderState();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    measurementService = null;
                    renderState();

                    Toast.makeText(
                            MainActivity.this,
                            R.string.measurement_connection_lost,
                            Toast.LENGTH_LONG
                    ).show();
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    if (!isBound) {
                        return;
                    }

                    releaseService();
                    connectService();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    releaseService();
                    renderState();
                    showConnectionError();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stopwatchText = findViewById(R.id.stopwatchText);
        statusText = findViewById(R.id.statusText);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        saveButton = findViewById(R.id.saveButton);
        resetButton = findViewById(R.id.resetButton);

        startButton.setOnClickListener(view -> {
            long startNs = SystemClock.elapsedRealtimeNanos();
            long wallTimeMs = System.currentTimeMillis();

            if (measurementService != null) {
                measurementService.startMeasurement(startNs, wallTimeMs);
            }
        });

        stopButton.setOnClickListener(view -> {
            if (measurementService != null) {
                measurementService.stopMeasurement();
            }
        });

        saveButton.setOnClickListener(view -> saveMeasurement());
        resetButton.setOnClickListener(view -> resetMeasurement());

        if (savedInstanceState == null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        connectService();
    }

    @Override
    protected void onStop() {
        releaseService();
        super.onStop();
    }

    private void connectService() {
        try {
            isBound = bindService(
                    new Intent(this, MeasurementService.class),
                    serviceConnection,
                    BIND_AUTO_CREATE
            );

            if (!isBound) {
                showConnectionError();
            }

        } catch (RuntimeException exception) {
            Log.e(TAG, "サービスへの接続に失敗しました", exception);

            isBound = false;
            showConnectionError();
        }

        renderState();
    }

    private void releaseService() {
        displayHandler.removeCallbacks(displayTask);

        if (measurementService != null) {
            measurementService.removeStateListener(stateListener);
            measurementService = null;
        }

        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    private void saveMeasurement() {
        MeasurementService target = measurementService;

        if (target == null || !target.canSave()) {
            return;
        }

        if (!target.isIncomplete()) {
            target.saveMeasurement();
            return;
        }

        new AlertDialog.Builder(this)
                .setMessage("中断した不完全なデータとして保存しますか？")
                .setNegativeButton("戻る", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (measurementService == target) {
                        target.saveMeasurement();
                    }
                })
                .show();
    }

    private void resetMeasurement() {
        MeasurementService target = measurementService;

        if (target == null || !target.canReset()) {
            return;
        }

        if (!target.hasUnsavedData()) {
            target.resetMeasurement();
            return;
        }

        new AlertDialog.Builder(this)
                .setMessage("未保存データを削除しますか？")
                .setNegativeButton("戻る", null)
                .setPositiveButton("削除", (dialog, which) -> {
                    if (measurementService == target) {
                        target.resetMeasurement();
                    }
                })
                .show();
    }

    private void renderState() {
        displayHandler.removeCallbacks(displayTask);

        MeasurementService.State state =
                measurementService == null
                        ? null
                        : measurementService.getState();

        startButton.setEnabled(
                state == MeasurementService.State.IDLE
        );

        stopButton.setEnabled(
                state == MeasurementService.State.STARTING
                        || state == MeasurementService.State.RUNNING
        );

        saveButton.setEnabled(
                measurementService != null
                        && measurementService.canSave()
        );

        resetButton.setEnabled(
                measurementService != null
                        && measurementService.canReset()
        );

        if (measurementService == null) {
            statusText.setText(R.string.service_connecting);
            return;
        }

        statusText.setText(measurementService.getStatusText());
        updateStopwatchText();

        String message = measurementService.consumeMessage();

        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }

        if (measurementService.isClockAdvancing()) {
            displayHandler.postDelayed(displayTask, DISPLAY_INTERVAL_MS);
        }
    }

    private void updateStopwatchText() {
        long elapsedNs = measurementService.getElapsedTimeNs();

        if (elapsedNs < 0L) {
            stopwatchText.setText(R.string.stopwatch_unknown);
            return;
        }

        long totalMilliseconds = elapsedNs / 1_000_000L;

        long minutes = totalMilliseconds / 60_000L;
        long seconds = (totalMilliseconds / 1_000L) % 60L;
        long milliseconds = totalMilliseconds % 1_000L;

        stopwatchText.setText(
                String.format(
                        Locale.ROOT,
                        "%02d:%02d:%03d",
                        minutes,
                        seconds,
                        milliseconds
                )
        );
    }

    private void showConnectionError() {
        Toast.makeText(
                this,
                R.string.measurement_connection_failed,
                Toast.LENGTH_LONG
        ).show();
    }
}