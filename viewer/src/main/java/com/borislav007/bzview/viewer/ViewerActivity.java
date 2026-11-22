package com.borislav007.bzview.viewer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.borislav007.bzview.core.CameraOption;
import com.borislav007.bzview.core.MjpegClient;
import com.borislav007.bzview.core.PairingInfo;
import com.borislav007.bzview.core.Protocol;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Viewer phone screen: shows the live video of a paired camera phone. */
public class ViewerActivity extends AppCompatActivity {

    private static final String PREFS = "bzview_viewer";
    private static final String KEY_URI = "camera_uri";
    private static final String KEY_BRIGHT = "bright";
    private static final long STATUS_HIDE_DELAY_MS = 3000;
    /** Bright mode: colors x1.35 plus a small lift (0-255 scale), enough to see into dim scenes. */
    private static final float BRIGHT_GAIN = 1.35f;
    private static final float BRIGHT_OFFSET = 25f;
    /** No new frame for this long means the stream has stalled. */
    private static final long STALL_MS = 2500;
    private static final long BADGE_TICK_MS = 500;
    private static final int COLOR_LIVE = 0xFF4CAF50;
    private static final int COLOR_WAIT = 0xFFFFB300;
    private static final int COLOR_STALLED = 0xFFF44336;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicReference<Bitmap> pendingFrame = new AtomicReference<>();

    private ImageView videoView;
    private ZoomPanController zoom;
    private int lastFrameWidth;
    private int lastFrameHeight;
    private TextView statusText;
    private TextView liveBadge;
    private String shownBadge = "";
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    // Written on the network thread, read on the UI thread.
    private volatile long lastFrameArrivalMs;
    private volatile long lastCameraTimeMs;
    private volatile long connectedAtMs;
    private final Runnable badgeTick = new Runnable() {
        @Override
        public void run() {
            updateLiveBadge();
            ui.postDelayed(this, BADGE_TICK_MS);
        }
    };
    private Button cameraButton;
    private Button torchButton;
    private Button disconnectButton;
    private Button fullscreenButton;
    private Button brightButton;
    private boolean bright;
    private Button exitFullscreenButton;
    private LinearLayout controlBar;
    private boolean fullscreen;

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
        zoom = new ZoomPanController(this, videoView, root);
        statusText = findViewById(R.id.statusText);
        liveBadge = findViewById(R.id.liveBadge);
        cameraButton = findViewById(R.id.cameraButton);
        torchButton = findViewById(R.id.torchButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        findViewById(R.id.scanButton).setOnClickListener(v ->
                scanLauncher.launch(new Intent(this, ScanActivity.class)));
        findViewById(R.id.addressButton).setOnClickListener(v -> showAddressDialog());
        cameraButton.setOnClickListener(v -> showCameraPicker());
        torchButton.setOnClickListener(v -> runCommand(Protocol.CMD_TORCH, Protocol.ARG_TOGGLE));
        disconnectButton.setOnClickListener(v -> forgetCamera());
        controlBar = findViewById(R.id.controlBar);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        exitFullscreenButton = findViewById(R.id.exitFullscreenButton);
        fullscreenButton.setOnClickListener(v -> setFullscreen(true));
        exitFullscreenButton.setOnClickListener(v -> setFullscreen(false));
        findViewById(R.id.exitButton).setOnClickListener(v -> finishAndRemoveTask());
        brightButton = findViewById(R.id.brightButton);
        brightButton.setOnClickListener(v -> setBright(!bright));
        setBright(getPreferences().getBoolean(KEY_BRIGHT, false));
        // Back leaves full screen first; a second Back exits the app as usual.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    setFullscreen(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

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
        ui.removeCallbacks(badgeTick);
        ui.post(badgeTick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(badgeTick);
        disconnect();
        super.onStop();
    }

    // ---------------------------------------------------------------- pairing

    private void pair(PairingInfo newInfo) {
        disconnect();
        zoom.reset();
        info = newInfo;
        getPreferences().edit().putString(KEY_URI, newInfo.toUri()).apply();
        connect();
    }

    private void forgetCamera() {
        disconnect();
        info = null;
        getPreferences().edit().remove(KEY_URI).apply();
        videoView.setImageDrawable(null);
        zoom.reset();
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
                connectedAtMs = SystemClock.elapsedRealtime();
                runOnUiThread(() -> {
                    connected = true;
                    showStatus(getString(R.string.connected_to, info != null ? info.host : ""), true);
                    updateButtons();
                    fetchStatus(0);
                });
            }

            @Override
            public void onFrame(byte[] jpeg, long cameraTimeMs) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (bitmap == null) {
                    return;
                }
                lastCameraTimeMs = cameraTimeMs;
                lastFrameArrivalMs = SystemClock.elapsedRealtime();
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
            updateLiveBadge();
            if (bitmap.getWidth() != lastFrameWidth || bitmap.getHeight() != lastFrameHeight) {
                lastFrameWidth = bitmap.getWidth();
                lastFrameHeight = bitmap.getHeight();
                zoom.refresh(); // picture shape changed (e.g. another camera): re-fit the zoom
            }
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

    /** Hides the system bars and the control bar, leaving only the video. */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (on) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
        controlBar.setVisibility(on ? View.GONE : View.VISIBLE);
        exitFullscreenButton.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /**
     * Shows whether the stream is alive. The badge is not part of the zoomed picture, so it is
     * always visible: green with the camera's capture time while frames arrive, amber while
     * connecting, red when no frame has arrived for a few seconds.
     */
    private void updateLiveBadge() {
        if (info == null) {
            liveBadge.setVisibility(View.GONE);
            shownBadge = "";
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long lastFrame = lastFrameArrivalMs;
        long since = connectedAtMs;
        boolean haveFrame = connected && lastFrame > 0 && lastFrame >= since;
        int color;
        String text;
        if (!connected) {
            color = COLOR_WAIT;
            text = getString(R.string.badge_connecting);
        } else if (haveFrame && now - lastFrame <= STALL_MS) {
            long t = lastCameraTimeMs > 0 ? lastCameraTimeMs : System.currentTimeMillis();
            color = COLOR_LIVE;
            text = getString(R.string.badge_live) + "  " + clockFormat.format(new Date(t));
        } else if (!haveFrame && now - since <= STALL_MS) {
            color = COLOR_WAIT;
            text = getString(R.string.badge_connecting);
        } else {
            long stalledSince = haveFrame ? lastFrame : since;
            color = COLOR_STALLED;
            text = getString(R.string.badge_no_video, (now - stalledSince) / 1000);
        }
        String key = color + text;
        liveBadge.setVisibility(View.VISIBLE);
        if (key.equals(shownBadge)) {
            return;
        }
        shownBadge = key;
        SpannableStringBuilder sb = new SpannableStringBuilder("\u25CF ").append(text);
        sb.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        liveBadge.setText(sb);
    }

    /** Brightens the picture on this phone only (the button shows what a tap will do). */
    private void setBright(boolean on) {
        bright = on;
        if (on) {
            ColorMatrix m = new ColorMatrix(new float[]{
                    BRIGHT_GAIN, 0, 0, 0, BRIGHT_OFFSET,
                    0, BRIGHT_GAIN, 0, 0, BRIGHT_OFFSET,
                    0, 0, BRIGHT_GAIN, 0, BRIGHT_OFFSET,
                    0, 0, 0, 1, 0});
            videoView.setColorFilter(new ColorMatrixColorFilter(m));
        } else {
            videoView.clearColorFilter();
        }
        brightButton.setText(on ? R.string.normal : R.string.bright);
        getPreferences().edit().putBoolean(KEY_BRIGHT, on).apply();
    }

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
