package com.borislav007.bzview.camera;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.borislav007.bzview.core.CameraOption;
import com.borislav007.bzview.core.QrCodes;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Camera phone screen: start/stop streaming and show the pairing QR code. */
public class CameraActivity extends AppCompatActivity implements StreamService.Listener {

    private static final long PREVIEW_INTERVAL_MS = 200;

    private StreamService service;
    private ImageView previewView;
    private ImageView qrView;
    private TextView statusText;
    private TextView addressText;
    private TextView webHintText;
    private TextView webAddressText;
    private MaterialButton shareWebButton;
    private TextView viewersText;
    private MaterialButton toggleButton;
    private MaterialButton cameraButton;
    private String shownUri;
    private long lastPreviewMs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((StreamService.LocalBinder) binder).getService();
            service.addListener(CameraActivity.this);
            service.refreshAddress();
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            refresh();
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasCameraPermission()) {
                    startStreaming();
                } else {
                    statusText.setText(R.string.camera_permission_needed);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        previewView = findViewById(R.id.previewView);
        qrView = findViewById(R.id.qrView);
        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        viewersText = findViewById(R.id.viewersText);
        toggleButton = findViewById(R.id.toggleButton);
        toggleButton.setOnClickListener(v -> onToggleClicked());
        cameraButton = findViewById(R.id.cameraButton);
        cameraButton.setOnClickListener(v -> showCameraPicker());
        webHintText = findViewById(R.id.webHintText);
        webAddressText = findViewById(R.id.webAddressText);
        shareWebButton = findViewById(R.id.shareWebButton);
        shareWebButton.setOnClickListener(v -> shareWebLink());
        findViewById(R.id.exitButton).setOnClickListener(v -> exitApp());
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, StreamService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.removeListener(this);
            service = null;
        }
        unbindService(connection);
        super.onStop();
    }

    // ---------------------------------------------------------------- actions

    private void onToggleClicked() {
        StreamService s = service;
        if (s == null) {
            return;
        }
        if (s.isStreaming()) {
            s.stopStreamingAndSelf();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!hasCameraPermission()) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            startStreaming();
        } else {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    /** Stops streaming, removes the service and closes the app. */
    private void exitApp() {
        StreamService s = service;
        if (s != null) {
            s.stopStreamingAndSelf();
        } else {
            stopService(new Intent(this, StreamService.class));
        }
        finishAndRemoveTask();
    }

    private void startStreaming() {
        requestBatteryExemptionOnce();
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
    }

    /**
     * Some phones stop background apps when the screen is locked, even foreground services.
     * Asking to be exempt from battery optimization keeps streaming going. Asked only once.
     */
    private void requestBatteryExemptionOnce() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())
                || Config.askedBatteryExemption(this)) {
            return;
        }
        Config.markAskedBatteryExemption(this);
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException ignored) {
            // Device has no such settings screen; streaming still works in most cases.
        }
    }

    private void shareWebLink() {
        StreamService s = service;
        String web = s != null && s.isStreaming() ? s.getWebUri() : null;
        if (web == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, web);
        startActivity(Intent.createChooser(send, getString(R.string.share_web_link_title)));
    }

    private void showCameraPicker() {
        final StreamService s = service;
        if (s == null || !s.isStreaming()) {
            return;
        }
        final List<CameraOption> options = s.getCameraOptions();
        if (options.isEmpty()) {
            return;
        }
        String[] labels = new String[options.size()];
        int checked = -1;
        String active = s.getActiveCameraId();
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (options.get(i).id.equals(active)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_camera)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    s.selectCamera(options.get(which).id);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- UI

    private void refresh() {
        StreamService s = service;
        boolean streaming = s != null && s.isStreaming();
        toggleButton.setEnabled(s != null);
        toggleButton.setText(streaming ? R.string.stop_streaming : R.string.start_streaming);
        cameraButton.setEnabled(streaming && !s.getCameraOptions().isEmpty());

        String uri = streaming ? s.getPairingUri() : null;
        if (uri != null) {
            if (!uri.equals(shownUri)) {
                Bitmap qr = QrCodes.generate(uri, 640);
                qrView.setImageBitmap(qr);
                shownUri = uri;
            }
            qrView.setVisibility(View.VISIBLE);
            addressText.setText(uri);
            addressText.setVisibility(View.VISIBLE);
        } else {
            shownUri = null;
            qrView.setVisibility(View.GONE);
            addressText.setVisibility(View.GONE);
        }
        String web = streaming ? s.getWebUri() : null;
        int webVisibility = web != null ? View.VISIBLE : View.GONE;
        webHintText.setVisibility(webVisibility);
        webAddressText.setVisibility(webVisibility);
        shareWebButton.setVisibility(webVisibility);
        if (web != null) {
            webAddressText.setText(web);
        }
        if (!streaming) {
            previewView.setImageDrawable(null);
        }

        String error = s != null ? s.getError() : null;
        if (error != null) {
            statusText.setText(error);
        } else if (!streaming) {
            statusText.setText(R.string.idle_hint);
        } else if (uri == null) {
            statusText.setText(R.string.no_network);
        } else {
            statusText.setText(R.string.scan_hint);
        }
        viewersText.setText(streaming ? getString(R.string.viewers_count, s.getViewerCount()) : "");
    }

    @Override
    public void onStatusChanged() {
        refresh();
    }

    @Override
    public void onPreviewFrame(byte[] jpeg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPreviewMs < PREVIEW_INTERVAL_MS) {
            return;
        }
        lastPreviewMs = now;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        final Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        if (bitmap != null) {
            runOnUiThread(() -> previewView.setImageBitmap(bitmap));
        }
    }
}
