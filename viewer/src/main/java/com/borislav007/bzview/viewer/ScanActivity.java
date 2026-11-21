package com.borislav007.bzview.viewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.borislav007.bzview.core.PairingInfo;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scans the pairing QR code shown on the camera phone. */
@ExperimentalGetImage
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "uri";

    private PreviewView previewView;
    private ExecutorService analysisExecutor;
    private BarcodeScanner scanner;
    private final AtomicBoolean done = new AtomicBoolean(false);

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        previewView = findViewById(R.id.scanPreview);
        analysisExecutor = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy imageProxy) {
        if (done.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(input)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && PairingInfo.parse(raw) != null && !done.getAndSet(true)) {
                            setResult(RESULT_OK, new Intent().putExtra(EXTRA_URI, raw.trim()));
                            finish();
                            return;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) {
            scanner.close();
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        super.onDestroy();
    }
}
