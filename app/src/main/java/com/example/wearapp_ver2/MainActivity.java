package com.example.wearapp_ver2;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/// extends AppCompatActivityでActivityの基本機能を引き継ぐ
public class MainActivity extends AppCompatActivity {

    ///画面表示を更新する間隔
    private static final long DISPLAY_INTERVAL_MS = 50L;

    private TextView stopwatchText;
    private Button startButton;
    private Button stopButton;
    private Button resetButton;

    private final Handler displayHandler = new Handler(Looper.getMainLooper());

    private boolean hasStarted = false;
    private boolean isRunning = false;

    private long startTimestampNs = 0L;
    private long elapsedTimeNs = 0L;

    private final Runnable displayTask = new Runnable() {
        @Override
        public void run() {
            if(!isRunning) {
                return;
            }
            updateStopwatchText();

            displayHandler.postDelayed(this, DISPLAY_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stopwatchText = findViewById(R.id.stopwatchText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        resetButton = findViewById(R.id.resetButton);

        updateStopwatchText();

        startButton.setOnClickListener(view -> startStopwatch());
        stopButton.setOnClickListener(view -> stopStopwatch());
        resetButton.setOnClickListener(view -> resetStopwatch());
    }

    private void startStopwatch() {
        if(hasStarted) {
            return;
        }
        startTimestampNs = SystemClock.elapsedRealtimeNanos();

        hasStarted = true;
        isRunning = true;
        elapsedTimeNs = 0L;

        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        displayHandler.post(displayTask);
    }

    private void stopStopwatch() {
        if(!isRunning) {
            return;
        }

        elapsedTimeNs = SystemClock.elapsedRealtimeNanos() - startTimestampNs;

        isRunning = false;

        displayHandler.removeCallbacks(displayTask);

        updateStopwatchText();

        stopButton.setEnabled(false);
        resetButton.setEnabled(true);
    }

    private void resetStopwatch() {
        if(isRunning || !hasStarted) {
            return;
        }

        hasStarted = false;
        elapsedTimeNs = 0L;

        updateStopwatchText();

        startButton.setEnabled(true);
        resetButton.setEnabled(false);
    }

    private void updateStopwatchText() {
        long currentElapsedNs;

        if (isRunning) {
           currentElapsedNs = SystemClock.elapsedRealtimeNanos() - startTimestampNs;
        } else {
            currentElapsedNs = elapsedTimeNs;
        }

        long totalMilliseconds = currentElapsedNs / 1_000_000L;

        long minutes = totalMilliseconds / 60_000L;
        long seconds = (totalMilliseconds / 1_000L) % 60L;
        long milliseconds = totalMilliseconds % 1_000L;

        String displayText = String.format(
                Locale.ROOT,
                "%02d:%02d:%03d",
                minutes,
                seconds,
                milliseconds
        );
        stopwatchText.setText(displayText);
    }

    @Override
    protected void onStart() {
        super.onStart();

        updateStopwatchText();

        if(isRunning) {
            displayHandler.post(displayTask);
        }
    }

    @Override
    protected void onStop() {
        displayHandler.removeCallbacks(displayTask);
        super.onStop();
    }

}

