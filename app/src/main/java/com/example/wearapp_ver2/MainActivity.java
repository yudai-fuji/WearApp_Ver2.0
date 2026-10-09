package com.example.wearapp_ver2;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/// extends AppCompatActivityでActivityの基本機能を引き継ぐ
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
