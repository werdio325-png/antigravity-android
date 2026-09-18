package com.antigravity.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_OVERLAY = 1234;

    private TextView tvPermissionStatus;
    private Button btnPermission;
    private Button btnToggleOverlay;
    private Button btnStopOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPermissionStatus = findViewById(R.id.tv_permission_status);
        btnPermission = findViewById(R.id.btn_permission);
        btnToggleOverlay = findViewById(R.id.btn_toggle_overlay);
        btnStopOverlay = findViewById(R.id.btn_stop_overlay);

        btnPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });

        btnToggleOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasOverlayPermission()) {
                    Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    Toast.makeText(MainActivity.this, "Панель запущена поверх окон!", Toast.LENGTH_SHORT).show();
                    moveTaskToBack(true);
                } else {
                    Toast.makeText(MainActivity.this, "Сначала предоставьте разрешение!", Toast.LENGTH_LONG).show();
                    requestOverlayPermission();
                }
            }
        });

        btnStopOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent serviceIntent = new Intent(MainActivity.this, OverlayService.class);
                stopService(serviceIntent);
                Toast.makeText(MainActivity.this, "Панель отключена", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUI();
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void updatePermissionUI() {
        if (hasOverlayPermission()) {
            tvPermissionStatus.setText("✓ Разрешение предоставлено");
            tvPermissionStatus.setTextColor(Color.parseColor("#22c55e"));
            btnPermission.setEnabled(false);
            btnPermission.setText("Разрешение активно");
        } else {
            tvPermissionStatus.setText("⚠ Требуется разрешение отображения поверх окон");
            tvPermissionStatus.setTextColor(Color.parseColor("#c084fc"));
            btnPermission.setEnabled(true);
            btnPermission.setText("Предоставить разрешение");
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, REQUEST_CODE_OVERLAY);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OVERLAY) {
            updatePermissionUI();
        }
    }
}
