package com.borislav007.bzview.viewer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.borislav007.bzview.core.CameraOption;
import com.borislav007.bzview.core.MjpegClient;
import com.borislav007.bzview.core.PairingInfo;
import com.borislav007.bzview.core.Protocol;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Viewer phone screen: shows the live video of a paired camera phone. */
public class ViewerActivity extends AppCompatActivity {

    private static final String PREFS = "bzview_viewer";
    private static final String KEY_URI = "camera_uri";
    private static final long STATUS_HIDE_DELAY_MS = 3000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicReference<Bitmap> pendingFrame = new AtomicReference<>();

    private ImageView videoView;
    private TextView statusText;
    private Button cameraButton;
    private Button torchButton;
    private Button disconnectButton;

    private PairingInfo info;
    private MjpegClient client;
    private boolean connected;
    private boolean hasTorch;
    private boolean torchOn;

    private final ActivityResultLauncher<Intent> scanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    PairingInfo scanned = PairingInfo.parse(result.getData().getStringExtra(ScanActivity.EXTRA_URI));
                    if (scanned != null) {
                        pair(scanned);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        videoView = findViewById(R.id.videoView);
        statusText = findViewById(R.id.statusText);
        cameraButton = findViewById(R.id.cameraButton);
        torchButton = findViewById(R.id.torchButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        findViewById(R.id.scanButton).setOnClickListener(v ->
                scanLauncher.launch(new Intent(this, ScanActivity.class)));
        findViewById(R.id.addressButton).setOnClickListener(v -> showAddressDialog());
        cameraButton.setOnClickListener(v -> showCameraPicker());
        torchButton.setOnClickListener(v -> runCommand(Protocol.CMD_TORCH, Protocol.ARG_TOGGLE));
        disconnectButton.setOnClickListener(v -> forgetCamera());

        info = PairingInfo.parse(getPreferences().getString(KEY_URI, null));
        showStatus(getString(info == null ? R.string.not_paired : R.string.connecting), false);
        updateButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (info != null && client == null) {
            connect();
        }
    }

    @Override
    protected void onStop() {
        disconnect();
        super.onStop();
    }

    // ---------------------------------------------------------------- pairing

    private void pair(PairingInfo newInfo) {
        disconnect();
        info = newInfo;
        getPreferences().edit().putString(KEY_URI, newInfo.toUri()).apply();
        connect();
    }

    private void forgetCamera() {
        disconnect();
        info = null;
        getPreferences().edit().remove(KEY_URI).apply();
        videoView.setImageDrawable(null);
        showStatus(getString(R.string.not_paired), false);
        updateButtons();
    }

    private void showAddressDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.address_hint);
        if (info != null) {
            input.setText(info.toUri());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.enter_address)
                .setView(input)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    PairingInfo parsed = PairingInfo.parse(input.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_LONG).show();
                    } else {
                        pair(parsed);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- connection

    private void connect() {
        if (info == null) {
            return;
        }
        disconnect();
        showStatus(getString(R.string.connecting_to, info.host), false);
        client = new MjpegClient(info, new MjpegClient.Listener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    connected = true;
                    showStatus(getString(R.string.connected_to, info != null ? info.host : ""), true);
                    updateButtons();
                    fetchStatus(0);
                });
            }

            @Override
            public void onFrame(byte[] jpeg) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap == null) {
                    return;
                }
                // Keep only the newest frame so a slow UI never builds up a backlog.
                if (pendingFrame.getAndSet(bitmap) == null) {
                    runOnUiThread(ViewerActivity.this::showPendingFrame);
                }
            }

            @Override
            public void onDisconnected(String reason) {
                runOnUiThread(() -> {
                    connected = false;
                    showStatus(getString(R.string.reconnecting, reason), false);
                    updateButtons();
                });
            }
        });
        client.start();
        updateButtons();
    }

    private void disconnect() {
        if (client != null) {
            client.stop();
            client = null;
        }
        connected = false;
        pendingFrame.set(null);
        updateButtons();
    }

    private void showPendingFrame() {
        Bitmap bitmap = pendingFrame.getAndSet(null);
        if (bitmap != null) {
            videoView.setImageBitmap(bitmap);
        }
    }

    // ---------------------------------------------------------------- remote controls

    private void runCommand(final String name, final String arg) {
        final PairingInfo target = info;
        if (target == null) {
            return;
        }
        new Thread(() -> {
            try {
                parseStatus(MjpegClient.sendCommand(target, name, arg));
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.command_failed, Toast.LENGTH_SHORT).show());
            }
        }, "bzview-command").start();
        // The camera applies the change a moment later; read the status again afterwards.
        fetchStatus(900);
    }

    /** Asks the camera phone for its camera list, then shows a picker. */
    private void showCameraPicker() {
        final PairingInfo target = info;
        if (target == null) {
            return;
        }
        new Thread(() -> {
            try {
                String status = MjpegClient.sendCommand(target, Protocol.CMD_STATUS, null);
                List<CameraOption> options = CameraOption.parseAll(status);
                String current = CameraOption.parseCurrent(status);
                runOnUiThread(() -> presentCameraPicker(target, options, current));
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.command_failed, Toast.LENGTH_SHORT).show());
            }
        }, "bzview-cameras").start();
    }

    private void presentCameraPicker(PairingInfo target, List<CameraOption> options, String current) {
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.no_cameras, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[options.size()];
        int checked = -1;
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (options.get(i).id.equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_camera)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    runCommand(Protocol.CMD_SELECT_CAMERA, options.get(which).id);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchStatus(long delayMs) {
        final PairingInfo target = info;
        if (target == null) {
            return;
        }
        ui.postDelayed(() -> new Thread(() -> {
            try {
                parseStatus(MjpegClient.sendCommand(target, Protocol.CMD_STATUS, null));
            } catch (IOException ignored) {
            }
        }, "bzview-status").start(), delayMs);
    }

    private void parseStatus(String text) {
        boolean torch = false;
        boolean flash = false;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if ("torch=1".equals(trimmed)) {
                torch = true;
            } else if ("hasTorch=1".equals(trimmed)) {
                flash = true;
            }
        }
        final boolean torchFinal = torch;
        final boolean flashFinal = flash;
        runOnUiThread(() -> {
            torchOn = torchFinal;
            hasTorch = flashFinal;
            updateButtons();
        });
    }

    // ---------------------------------------------------------------- UI helpers

    private void updateButtons() {
        cameraButton.setEnabled(connected);
        torchButton.setEnabled(connected && hasTorch);
        torchButton.setText(torchOn ? R.string.torch_off : R.string.torch_on);
        disconnectButton.setEnabled(info != null);
    }

    private void showStatus(String text, boolean autoHide) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatus);
        if (autoHide) {
            ui.postDelayed(hideStatus, STATUS_HIDE_DELAY_MS);
        }
    }

    private final Runnable hideStatus = () -> {
        if (connected) {
            statusText.setVisibility(View.GONE);
        }
    };

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
