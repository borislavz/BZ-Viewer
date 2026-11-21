package com.borislav007.bzview.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.borislav007.bzview.core.CameraServer;
import com.borislav007.bzview.core.NetUtil;
import com.borislav007.bzview.core.PairingInfo;
import com.borislav007.bzview.core.Protocol;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the camera and the streaming server. Runs as a foreground service so the phone
 * keeps streaming while the screen is off or the app is in the background.
 */
public class StreamService extends LifecycleService {

    public static final String ACTION_STOP = "com.borislav007.bzview.camera.STOP";

    private static final String CHANNEL_ID = "stream";
    private static final int NOTIFICATION_ID = 1;
    private static final long MIN_FRAME_INTERVAL_MS = 66; // about 15 frames per second
    private static final int JPEG_QUALITY = 70;

    public interface Listener {
        /** Always called on the main thread. */
        void onStatusChanged();

        /** Called on the analysis thread with a JPEG frame for the on-screen preview. */
        void onPreviewFrame(byte[] jpeg);
    }

    public class LocalBinder extends Binder {
        public StreamService getService() {
            return StreamService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ExecutorService analysisExecutor;
    private CameraServer server;
    private ProcessCameraProvider provider;
    private Camera camera;
    private PowerManager.WakeLock wakeLock;
    private String token;
    private long lastFrameMs;

    private volatile boolean streaming;
    private volatile int viewerCount;
    private volatile String error;
    private volatile String host;
    private volatile boolean useFront;
    private volatile boolean torchOn;
    private volatile boolean hasTorch;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopStreamingAndSelf();
            return START_NOT_STICKY;
        }
        startStreaming();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- public API (main thread)

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isStreaming() {
        return streaming;
    }

    public int getViewerCount() {
        return viewerCount;
    }

    @Nullable
    public String getError() {
        return error;
    }

    /** Address viewers must enter or scan, or null when there is no network address. */
    @Nullable
    public String getPairingUri() {
        String h = host;
        if (!streaming || h == null) {
            return null;
        }
        return new PairingInfo(h, Protocol.DEFAULT_PORT, token).toUri();
    }

    public void refreshAddress() {
        host = NetUtil.localIpv4();
        if (streaming) {
            updateNotification();
        }
        notifyStatus();
    }

    public void stopStreamingAndSelf() {
        stopStreaming();
        stopSelf();
    }

    // ---------------------------------------------------------------- start / stop

    private void startStreaming() {
        if (streaming) {
            return;
        }
        error = null;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (RuntimeException e) {
            setError("Cannot run in background: " + e.getMessage());
            stopSelf();
            return;
        }

        token = Config.token(this);
        server = new CameraServer(Protocol.DEFAULT_PORT, token, new CameraServer.Listener() {
            @Override
            public void onViewerCountChanged(int count) {
                viewerCount = count;
                main.post(() -> {
                    if (streaming) {
                        updateNotification();
                    }
                    notifyStatus();
                });
            }

            @Override
            public String onCommand(String name, String arg) {
                return handleCommand(name, arg);
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            server = null;
            setError("Cannot open port " + Protocol.DEFAULT_PORT + ": " + e.getMessage());
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        streaming = true;
        host = NetUtil.localIpv4();
        acquireWakeLock();
        analysisExecutor = Executors.newSingleThreadExecutor();
        updateNotification();
        bindCamera();
        notifyStatus();
    }

    private void stopStreaming() {
        boolean wasStreaming = streaming;
        streaming = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (provider != null) {
            try {
                provider.unbindAll();
            } catch (RuntimeException ignored) {
            }
        }
        camera = null;
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        releaseWakeLock();
        viewerCount = 0;
        torchOn = false;
        hasTorch = false;
        if (wasStreaming) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        }
        notifyStatus();
    }

    // ---------------------------------------------------------------- camera

    private void bindCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
            } catch (Exception e) {
                setError("Camera unavailable: " + e.getMessage());
                return;
            }
            if (streaming) {
                bindUseCases();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        try {
            provider.unbindAll();
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setResolutionSelector(new ResolutionSelector.Builder()
                            .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build();
            analysis.setAnalyzer(analysisExecutor, this::analyze);
            CameraSelector selector = useFront
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;
            camera = provider.bindToLifecycle(this, selector, analysis);
            hasTorch = camera.getCameraInfo().hasFlashUnit();
            torchOn = false;
            error = null;
            notifyStatus();
        } catch (Exception e) {
            setError("Cannot start camera: " + e.getMessage());
        }
    }

    private void analyze(ImageProxy image) {
        try {
            CameraServer s = server;
            if (s == null || (s.getViewerCount() == 0 && listeners.isEmpty())) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS) {
                return;
            }
            lastFrameMs = now;

            Bitmap bitmap = image.toBitmap();
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                bitmap.recycle();
                bitmap = rotated;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            bitmap.recycle();
            byte[] jpeg = bos.toByteArray();

            s.publishFrame(jpeg);
            for (Listener l : listeners) {
                l.onPreviewFrame(jpeg);
            }
        } catch (RuntimeException ignored) {
            // drop this frame
        } finally {
            image.close();
        }
    }

    // ---------------------------------------------------------------- remote commands (server thread)

    private String handleCommand(String name, String arg) {
        if (Protocol.CMD_SWITCH_CAMERA.equals(name)) {
            main.post(() -> {
                useFront = !useFront;
                if (streaming && provider != null) {
                    bindUseCases();
                }
            });
        } else if (Protocol.CMD_TORCH.equals(name)) {
            main.post(() -> setTorch(arg));
        }
        return statusText();
    }

    private void setTorch(String arg) {
        Camera c = camera;
        if (c == null || !hasTorch) {
            return;
        }
        boolean on = Protocol.ARG_TOGGLE.equals(arg) ? !torchOn : "1".equals(arg);
        torchOn = on;
        c.getCameraControl().enableTorch(on);
    }

    private String statusText() {
        return "front=" + (useFront ? 1 : 0)
                + "\ntorch=" + (torchOn ? 1 : 0)
                + "\nhasTorch=" + (hasTorch ? 1 : 0)
                + "\nviewers=" + viewerCount;
    }

    // ---------------------------------------------------------------- helpers

    private void setError(String message) {
        error = message;
        main.post(this::notifyStatus);
    }

    private void notifyStatus() {
        for (Listener l : listeners) {
            l.onStatusChanged();
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bzview:stream");
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, CameraActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, StreamService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = getString(R.string.notification_text, viewerCount);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, getString(R.string.stop), stop)
                .build();
    }

    private void updateNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, buildNotification());
    }
}
