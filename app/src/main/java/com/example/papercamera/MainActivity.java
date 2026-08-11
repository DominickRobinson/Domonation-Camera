package com.domonation.camera;

import android.Manifest;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.CountDownTimer;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Range;
import android.util.Size;
import android.util.Log;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends ComponentActivity {
    private enum Mode { PHOTO, VIDEO, TIMELAPSE }
    private enum ControlPanel { NONE, ZOOM, EXPOSURE }

    private static final String PREFS = "paper_camera";
    private static final String KEY_TREE = "save_tree";
    private static final String KEY_THUMB = "exif_thumbnail";
    private static final String KEY_REVIEW = "review_before_save";
    private static final String KEY_TIMER = "timer_seconds";
    private static final String KEY_TIMER_ENABLED = "timer_enabled";
    private static final String KEY_LAPSE = "timelapse_seconds";
    private static final String KEY_LAPSE_FPS = "timelapse_fps";
    private static final String KEY_LAPSE_CUSTOM = "timelapse_seconds_custom";
    private static final String KEY_LAPSE_FPS_CUSTOM = "timelapse_fps_custom";
    private static final String KEY_VIDEO_AUDIO = "video_audio";
    private static final String KEY_VOLUME_SHUTTER = "volume_shutter";
    private static final String KEY_AUDIO_ASKED = "audio_asked";
    private static final String KEY_GALLERY_ROWS = "gallery_rows";
    private static final String KEY_GALLERY_COLUMNS = "gallery_columns";

    private PreviewView previewView;
    private TextView statusView;
    private TextView loadingView;
    private ImageButton shutterButton;
    private ImageButton flashButton;
    private ImageButton modeButton;
    private ImageButton timerButton;
    private ImageButton zoomToggleButton;
    private ImageButton exposureToggleButton;
    private TextView timerBadge;
    private View captureControls;
    private View zoomControls;
    private View exposureControls;
    private SeekBar exposureBar;
    private SeekBar zoomBar;
    private TextView zoomLabel;
    private TextView exposureLabel;
    private TickMarkView zoomTicks;
    private TickMarkView exposureTicks;
    private ScaleGestureDetector zoomGestureDetector;
    private boolean zoomGestureActive;
    private boolean dismissingControlPanelTouch;
    private boolean updatingZoomBar;
    private int[] zoomTickProgresses = new int[]{0, 1000};
    private int[] exposureTickProgresses = new int[]{0};
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recorder recorder;
    private Recording recording;
    private Mode mode = Mode.PHOTO;
    private ControlPanel controlPanel = ControlPanel.NONE;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private Size embeddedThumbnailSize = new Size(0, 0);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideStatusRunnable = () -> {
        if (statusView != null) statusView.setVisibility(View.GONE);
    };
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<File> timelapseFrameFiles = new ArrayList<>();
    private final Runnable timelapseCaptureRunnable = this::captureTimelapseFrame;
    private boolean timelapseRunning;
    private boolean timelapseCaptureInFlight;
    private boolean timelapseFinalizing;
    private boolean internalDialogVisible;
    private int timelapseFrames;
    private boolean lastSaveUsedFallback;
    private CountDownTimer shutterTimer;
    private int targetRotation = Surface.ROTATION_0;
    private OrientationEventListener orientationListener;
    private final ArrayList<View> orientationViews = new ArrayList<>();

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera(); else setStatus("Camera permission is required");
            });

    private final ActivityResultLauncher<String> audioPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) setStatus("Audio denied · recording silently");
                startVideo();
            });

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs().edit().putString(KEY_TREE, uri.toString()).apply();
                setStatus("Save folder selected");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setWindowAnimations(0);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        statusView = findViewById(R.id.status);
        loadingView = findViewById(R.id.camera_loading);
        shutterButton = findViewById(R.id.shutter);
        flashButton = findViewById(R.id.flash);
        modeButton = findViewById(R.id.mode);
        timerButton = findViewById(R.id.timer_toggle);
        timerBadge = findViewById(R.id.timer_badge);
        zoomToggleButton = findViewById(R.id.zoom_toggle);
        exposureToggleButton = findViewById(R.id.exposure_toggle);
        captureControls = findViewById(R.id.capture_controls);
        zoomControls = findViewById(R.id.zoom_controls);
        exposureControls = findViewById(R.id.exposure_controls);
        exposureBar = findViewById(R.id.exposure);
        zoomBar = findViewById(R.id.zoom_bar);
        exposureLabel = findViewById(R.id.exposure_label);
        zoomTicks = findViewById(R.id.zoom_ticks);
        exposureTicks = findViewById(R.id.exposure_ticks);
        int[] rotatingIds = {R.id.timer_toggle, R.id.timer_badge, R.id.zoom_toggle,
                R.id.exposure_toggle, R.id.settings, R.id.mode, R.id.shutter, R.id.flash, R.id.gallery,
                R.id.zoom_out, R.id.zoom_label, R.id.zoom_in, R.id.exposure_down,
                R.id.exposure_label, R.id.exposure_up, R.id.camera_loading, R.id.status};
        for (int id : rotatingIds) orientationViews.add(findViewById(id));
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        zoomLabel = findViewById(R.id.zoom_label);
        zoomGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                zoomGestureActive = true;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return false;
                float current = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                setZoomRatio(current * detector.getScaleFactor());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                handler.postDelayed(() -> zoomGestureActive = false, 150);
            }
        });
        targetRotation = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) return;
                int next;
                if (degrees >= 315 || degrees < 45) next = Surface.ROTATION_0;
                else if (degrees < 135) next = Surface.ROTATION_270;
                else if (degrees < 225) next = Surface.ROTATION_180;
                else next = Surface.ROTATION_90;
                if (targetRotation == next) return;
                targetRotation = next;
                if (imageCapture != null) imageCapture.setTargetRotation(next);
                if (videoCapture != null) videoCapture.setTargetRotation(next);
                applyUiRotation(surfaceRotationDegrees(next));
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        applyUiRotation(surfaceRotationDegrees(targetRotation));

        modeButton.setOnClickListener(v -> setMode(
                mode == Mode.PHOTO ? Mode.VIDEO : mode == Mode.VIDEO ? Mode.TIMELAPSE : Mode.PHOTO));
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        timerButton.setOnClickListener(v -> cycleTimer());
        zoomToggleButton.setOnClickListener(v -> setControlPanel(
                controlPanel == ControlPanel.ZOOM ? ControlPanel.NONE : ControlPanel.ZOOM));
        exposureToggleButton.setOnClickListener(v -> setControlPanel(
                controlPanel == ControlPanel.EXPOSURE ? ControlPanel.NONE : ControlPanel.EXPOSURE));
        flashButton.setOnClickListener(v -> cycleFlash());
        findViewById(R.id.gallery).setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));
        shutterButton.setOnClickListener(v -> onShutter());
        previewView.setOnTouchListener(this::handlePreviewTouch);
        findViewById(R.id.zoom_out).setOnClickListener(v -> stepZoom(false));
        findViewById(R.id.zoom_in).setOnClickListener(v -> stepZoom(true));
        findViewById(R.id.exposure_down).setOnClickListener(v -> stepExposure(false));
        findViewById(R.id.exposure_up).setOnClickListener(v -> stepExposure(true));
        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) setExposure(progress);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || updatingZoomBar || camera == null ||
                        camera.getCameraInfo().getZoomState().getValue() == null) return;
                androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                float fraction = progress / (float) Math.max(1, bar.getMax());
                setZoomRatio(state.getMinZoomRatio() +
                        fraction * (state.getMaxZoomRatio() - state.getMinZoomRatio()));
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });

        updateModeUi();
        updateTimerUi();
        setControlPanel(ControlPanel.NONE);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (controlPanel != ControlPanel.NONE) {
                    setControlPanel(ControlPanel.NONE);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private float surfaceRotationDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90f;
        if (rotation == Surface.ROTATION_180) return 180f;
        if (rotation == Surface.ROTATION_270) return 270f;
        return 0f;
    }

    private void applyUiRotation(float degrees) {
        for (View view : orientationViews) {
            view.animate().cancel();
            view.setRotation(degrees);
        }
    }

    private void setMode(Mode selected) {
        if (recording != null || timelapseRunning || timelapseFinalizing) return;
        mode = selected;
        updateModeUi();
        startCamera();
    }

    private void updateModeUi() {
        modeButton.setImageResource(mode == Mode.PHOTO ? R.drawable.ic_video :
                mode == Mode.VIDEO ? R.drawable.ic_timelapse : R.drawable.ic_photo);
        modeButton.setContentDescription(mode == Mode.PHOTO ? "Switch to video mode" :
                mode == Mode.VIDEO ? "Switch to timelapse mode" : "Switch to photo mode");
        shutterButton.setImageResource(mode == Mode.PHOTO ? R.drawable.ic_photo :
                mode == Mode.VIDEO ? R.drawable.ic_video : R.drawable.ic_timelapse);
        shutterButton.setContentDescription(mode == Mode.VIDEO ? "Start video" :
                mode == Mode.TIMELAPSE ? "Start timelapse" : "Take photo");
    }

    private void setControlPanel(ControlPanel selected) {
        if ((recording != null || timelapseRunning || timelapseFinalizing) &&
                selected != ControlPanel.NONE) return;
        controlPanel = selected;
        captureControls.setVisibility(selected == ControlPanel.NONE ? View.VISIBLE : View.GONE);
        zoomControls.setVisibility(selected == ControlPanel.ZOOM ? View.VISIBLE : View.GONE);
        exposureControls.setVisibility(selected == ControlPanel.EXPOSURE ? View.VISIBLE : View.GONE);
        styleHeaderControl(zoomToggleButton, selected == ControlPanel.ZOOM);
        styleHeaderControl(exposureToggleButton, selected == ControlPanel.EXPOSURE);
        zoomToggleButton.setContentDescription(selected == ControlPanel.ZOOM ?
                "Hide zoom controls" : "Show zoom controls");
        exposureToggleButton.setContentDescription(selected == ControlPanel.EXPOSURE ?
                "Hide exposure controls" : "Show exposure controls");
    }

    private void styleHeaderControl(ImageButton button, boolean active) {
        button.setBackgroundColor(ContextCompat.getColor(this, active ? R.color.ink : R.color.paper));
        button.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, active ? R.color.paper : R.color.ink)));
    }

    private void startCamera() {
        setStatus("Starting camera…");
        shutterButton.setEnabled(false);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        Executor main = ContextCompat.getMainExecutor(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception error) {
                setStatus("Camera error: " + error.getMessage());
            }
        }, main);
    }

    private void bindCamera() throws Exception {
        embeddedThumbnailSize = findRearCameraThumbnailSize();
        // The activity stays portrait so the control bars remain fixed. Keep the preview
        // in that display coordinate system as well; rotating this use case independently
        // makes the sensor image turn inside the fixed viewfinder on some camera HALs.
        previewUseCase = new Preview.Builder().setTargetRotation(Surface.ROTATION_0).build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
        cameraProvider.unbindAll();

        if (mode == Mode.VIDEO) {
            recorder = new Recorder.Builder().build();
            videoCapture = VideoCapture.withOutput(recorder);
            videoCapture.setTargetRotation(targetRotation);
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, videoCapture);
        } else {
            ImageCapture.Builder builder = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .setFlashMode(flashMode);
            boolean thumbnailOn = prefs().getBoolean(KEY_THUMB, true);
            Size requested = thumbnailOn ? embeddedThumbnailSize : new Size(0, 0);
            Camera2Interop.Extender<ImageCapture> interop = new Camera2Interop.Extender<>(builder);
            interop.setCaptureRequestOption(CaptureRequest.JPEG_THUMBNAIL_SIZE, requested);
            if (thumbnailOn && requested.getWidth() > 0) {
                interop.setCaptureRequestOption(CaptureRequest.JPEG_THUMBNAIL_QUALITY, (byte) 85);
            }
            imageCapture = builder.build();
            imageCapture.setTargetRotation(targetRotation);
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, imageCapture);
        }
        configureExposure();
        configureZoom();
        shutterButton.setEnabled(true);
        setStatus("Ready · tap viewfinder to focus");
    }

    private void configureExposure() {
        if (camera == null) return;
        Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
        int min = range.getLower();
        int max = range.getUpper();
        exposureBar.setMax(Math.max(0, max - min));
        exposureBar.setProgress(-min);
        exposureBar.setEnabled(max > min);
        exposureLabel.setText("+0");
        int defaultProgress = -min;
        exposureTickProgresses = buildTickProgresses(exposureBar.getMax(), 13, defaultProgress);
        exposureTicks.configure(tickFractions(exposureTickProgresses, exposureBar.getMax()),
                max == min ? 0.5f : defaultProgress / (float) (max - min));
    }

    private void setExposure(int progress) {
        if (camera == null) return;
        Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
        int index = range.getLower() + progress;
        camera.getCameraControl().setExposureCompensationIndex(index);
        exposureLabel.setText((index >= 0 ? "+" : "") + index);
        setStatus("Exposure " + (index >= 0 ? "+" : "") + index);
    }

    private void stepExposure(boolean increase) {
        if (camera == null || !exposureBar.isEnabled()) return;
        int next = nextTickProgress(exposureBar.getProgress(), exposureTickProgresses, increase);
        if (next == exposureBar.getProgress()) return;
        exposureBar.setProgress(next);
        setExposure(next);
    }

    private void configureZoom() {
        if (camera == null) return;
        camera.getCameraInfo().getZoomState().observe(this, state -> {
            if (state == null) return;
            zoomLabel.setText(String.format(Locale.US, "%.1f×", state.getZoomRatio()));
            float span = state.getMaxZoomRatio() - state.getMinZoomRatio();
            float fraction = span <= 0 ? 0f :
                    (state.getZoomRatio() - state.getMinZoomRatio()) / span;
            updatingZoomBar = true;
            zoomBar.setProgress(Math.round(fraction * zoomBar.getMax()));
            updatingZoomBar = false;
            float defaultFraction = span <= 0 ? 0f : (1f - state.getMinZoomRatio()) / span;
            int defaultProgress = Math.round(defaultFraction * zoomBar.getMax());
            zoomTickProgresses = buildTickProgresses(zoomBar.getMax(), 9, defaultProgress);
            zoomTicks.configure(tickFractions(zoomTickProgresses, zoomBar.getMax()), defaultFraction);
            zoomBar.setEnabled(span > 0);
        });
    }

    private void stepZoom(boolean zoomIn) {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return;
        androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        int nextProgress = nextTickProgress(zoomBar.getProgress(), zoomTickProgresses, zoomIn);
        if (nextProgress == zoomBar.getProgress()) return;
        float fraction = nextProgress / (float) Math.max(1, zoomBar.getMax());
        setZoomRatio(state.getMinZoomRatio() +
                fraction * (state.getMaxZoomRatio() - state.getMinZoomRatio()));
    }

    private int[] buildTickProgresses(int max, int preferredCount, int majorProgress) {
        if (max <= 0) return new int[]{0};
        int count = Math.min(preferredCount, max + 1);
        ArrayList<Integer> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int value = Math.round(i * max / (float) (count - 1));
            if (!values.contains(value)) values.add(value);
        }
        int clampedMajor = Math.max(0, Math.min(max, majorProgress));
        if (!values.contains(clampedMajor)) values.add(clampedMajor);
        values.sort(Integer::compareTo);
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private float[] tickFractions(int[] progresses, int max) {
        float[] fractions = new float[progresses.length];
        for (int i = 0; i < progresses.length; i++) {
            fractions[i] = max <= 0 ? 0f : progresses[i] / (float) max;
        }
        return fractions;
    }

    private int nextTickProgress(int current, int[] ticks, boolean increase) {
        if (increase) {
            for (int tick : ticks) if (tick > current) return tick;
            return current;
        }
        for (int i = ticks.length - 1; i >= 0; i--) if (ticks[i] < current) return ticks[i];
        return current;
    }

    private void setZoomRatio(float requested) {
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) return;
        androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        float clamped = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), requested));
        camera.getCameraControl().setZoomRatio(clamped);
    }

    private boolean handlePreviewTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && controlPanel != ControlPanel.NONE) {
            dismissingControlPanelTouch = true;
            setControlPanel(ControlPanel.NONE);
            return true;
        }
        if (dismissingControlPanelTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dismissingControlPanelTouch = false;
            }
            return true;
        }
        zoomGestureDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1 || zoomGestureActive) return true;
        return focusAtTouch(view, event);
    }

    private boolean focusAtTouch(View view, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || camera == null) return true;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
        FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
        camera.getCameraControl().startFocusAndMetering(action);
        return true;
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) flashMode = ImageCapture.FLASH_MODE_AUTO;
        else if (flashMode == ImageCapture.FLASH_MODE_AUTO) flashMode = ImageCapture.FLASH_MODE_ON;
        else flashMode = ImageCapture.FLASH_MODE_OFF;
        flashButton.setImageResource(flashMode == ImageCapture.FLASH_MODE_OFF ? R.drawable.ic_flash_off :
                flashMode == ImageCapture.FLASH_MODE_AUTO ? R.drawable.ic_flash_auto : R.drawable.ic_flash_on);
        flashButton.setContentDescription(flashMode == ImageCapture.FLASH_MODE_OFF ? "Flash off" :
                flashMode == ImageCapture.FLASH_MODE_AUTO ? "Flash automatic" : "Flash on");
        if (mode == Mode.VIDEO && camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(flashMode == ImageCapture.FLASH_MODE_ON);
        } else if (imageCapture != null) imageCapture.setFlashMode(flashMode);
    }

    private void onShutter() {
        if (controlPanel != ControlPanel.NONE) setControlPanel(ControlPanel.NONE);
        if (timelapseFinalizing) {
            setStatus("Creating timelapse video…");
            return;
        }
        if (shutterTimer != null) {
            shutterTimer.cancel();
            shutterTimer = null;
            shutterButton.setEnabled(true);
            setStatus("Timer cancelled");
            return;
        }
        if (mode == Mode.VIDEO) {
            if (recording == null) runWithTimer(this::startVideoWithPermission); else recording.stop();
        } else if (mode == Mode.TIMELAPSE) {
            if (timelapseRunning) stopTimelapse(); else runWithTimer(this::startTimelapse);
        } else runWithTimer(() -> capturePhoto(true));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (isVolumeShutterKey(event.getKeyCode()) && volumeShutterEnabled()) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                onShutter();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isVolumeShutterKey(int keyCode) {
        return keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private boolean volumeShutterEnabled() {
        return prefs().getBoolean(KEY_VOLUME_SHUTTER, true) && !internalDialogVisible;
    }

    private void startVideoWithPermission() {
        if (!prefs().getBoolean(KEY_VIDEO_AUDIO, true)) {
            startVideo();
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted && !prefs().getBoolean(KEY_AUDIO_ASKED, false)) {
            prefs().edit().putBoolean(KEY_AUDIO_ASKED, true).apply();
            audioPermission.launch(Manifest.permission.RECORD_AUDIO);
        } else startVideo();
    }

    private void runWithTimer(Runnable action) {
        if (!prefs().getBoolean(KEY_TIMER_ENABLED, false)) { action.run(); return; }
        int seconds = prefs().getInt(KEY_TIMER, 3);
        if (shutterTimer != null) shutterTimer.cancel();
        shutterTimer = new CountDownTimer(seconds * 1000L + 200, 250) {
            private int shown = -1;
            @Override public void onTick(long remainingMs) {
                int remaining = Math.max(1, (int) Math.ceil((remainingMs - 150) / 1000.0));
                if (remaining != shown) {
                    shown = remaining;
                    setStatus("Timer · " + remaining);
                }
            }
            @Override public void onFinish() {
                shutterTimer = null;
                action.run();
            }
        }.start();
    }

    private void updateTimerUi() {
        boolean enabled = prefs().getBoolean(KEY_TIMER_ENABLED, false);
        int seconds = prefs().getInt(KEY_TIMER, 3);
        timerButton.setImageResource(enabled ? R.drawable.ic_timer_on : R.drawable.ic_timer_off);
        timerButton.setContentDescription(enabled ? "Timer on, " + seconds + " seconds" : "Timer off");
        timerBadge.setText(enabled ? String.valueOf(seconds) : "");
        timerBadge.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void cycleTimer() {
        if (shutterTimer != null) {
            shutterTimer.cancel();
            shutterTimer = null;
            shutterButton.setEnabled(true);
        }
        boolean enabled = prefs().getBoolean(KEY_TIMER_ENABLED, false);
        int seconds = prefs().getInt(KEY_TIMER, 3);
        SharedPreferences.Editor edit = prefs().edit();
        if (!enabled) {
            edit.putBoolean(KEY_TIMER_ENABLED, true).putInt(KEY_TIMER, 3).apply();
        } else if (seconds == 3) {
            edit.putBoolean(KEY_TIMER_ENABLED, true).putInt(KEY_TIMER, 10).apply();
        } else {
            edit.putBoolean(KEY_TIMER_ENABLED, false).apply();
        }
        updateTimerUi();
    }

    private void capturePhoto(boolean allowReview) {
        if (imageCapture == null) return;
        imageCapture.setTargetRotation(targetRotation);
        shutterButton.setEnabled(false);
        File temp = new File(getCacheDir(), newName("PAPER", ".jpg"));
        ImageCapture.OutputFileOptions output = new ImageCapture.OutputFileOptions.Builder(temp).build();
        imageCapture.takePicture(output, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                if (allowReview && prefs().getBoolean(KEY_REVIEW, false)) showPhotoReview(temp);
                else saveCompletedCapture(temp, "image/jpeg", true);
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
                setStatus("Capture failed: " + error.getMessage());
                shutterButton.setEnabled(true);
            }
        });
    }

    private void showPhotoReview(File temp) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageURI(Uri.fromFile(temp));
        showFullScreenReview("Review photo", image,
                () -> saveCompletedCapture(temp, "image/jpeg", true),
                () -> {
                    temp.delete();
                    shutterButton.setEnabled(true);
                    setStatus("Ready · tap viewfinder to focus");
                });
    }

    private void startVideo() {
        if (recorder == null) return;
        if (videoCapture != null) videoCapture.setTargetRotation(targetRotation);
        File temp = new File(getCacheDir(), newName("VIDEO", ".mp4"));
        PendingRecording pending = recorder.prepareRecording(this, new FileOutputOptions.Builder(temp).build());
        if (prefs().getBoolean(KEY_VIDEO_AUDIO, true) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        shutterButton.setContentDescription("Stop video");
        setStatus("Recording…");
        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
                recording = null;
                shutterButton.setContentDescription("Start video");
                if (done.hasError()) {
                    temp.delete();
                    setStatus("Video failed: " + done.getError());
                } else if (prefs().getBoolean(KEY_REVIEW, false)) showVideoReview(temp);
                else saveCompletedCapture(temp, "video/mp4", false);
            }
        });
    }

    private void showVideoReview(File temp) {
        VideoView video = new VideoView(this);
        video.setVideoURI(Uri.fromFile(temp));
        video.setOnPreparedListener(player -> { player.setLooping(true); video.start(); });
        showFullScreenReview("Review video", video,
                () -> saveCompletedCapture(temp, "video/mp4", false),
                () -> {
                    temp.delete();
                    shutterButton.setEnabled(true);
                    setStatus("Ready · tap viewfinder to focus");
                });
    }

    private void showFullScreenReview(String title, View media, Runnable save, Runnable discard) {
        internalDialogVisible = true;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.paper));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.top_rule);
        Button close = reviewTextButton("×");
        close.setTextSize(34);
        header.addView(close, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(ContextCompat.getColor(this, R.color.ink));
        heading.setTextSize(21);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(64), 1));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        media.setBackgroundColor(ContextCompat.getColor(this, R.color.paper));
        page.addView(media, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setBackgroundResource(R.drawable.top_rule);
        actions.setGravity(android.view.Gravity.CENTER);
        actions.setPadding(dp(16), dp(8), dp(16), dp(8));
        Button retake = reviewTextButton("RETAKE");
        Button keep = reviewTextButton("SAVE");
        actions.addView(retake, new LinearLayout.LayoutParams(0, dp(64), 1));
        actions.addView(keep, new LinearLayout.LayoutParams(0, dp(64), 1));
        page.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        boolean[] resolved = {false};
        Runnable stopMedia = () -> { if (media instanceof VideoView) ((VideoView) media).stopPlayback(); };
        close.setOnClickListener(v -> {
            if (!resolved[0]) { resolved[0] = true; stopMedia.run(); discard.run(); }
            dialog.dismiss();
        });
        retake.setOnClickListener(v -> {
            if (!resolved[0]) { resolved[0] = true; stopMedia.run(); discard.run(); }
            dialog.dismiss();
        });
        keep.setOnClickListener(v -> {
            if (!resolved[0]) { resolved[0] = true; stopMedia.run(); save.run(); }
            dialog.dismiss();
        });
        dialog.setOnCancelListener(d -> {
            if (!resolved[0]) { resolved[0] = true; stopMedia.run(); discard.run(); }
        });
        dialog.setOnDismissListener(d -> internalDialogVisible = false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (!resolved[0]) { resolved[0] = true; stopMedia.run(); discard.run(); }
                dialog.dismiss();
            }
            return true;
        });
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(page);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private Button reviewTextButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(ContextCompat.getColor(this, R.color.ink));
        button.setTextSize(16);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startTimelapse() {
        timelapseRunning = true;
        timelapseFinalizing = false;
        timelapseCaptureInFlight = false;
        timelapseFrames = 0;
        for (File frame : timelapseFrameFiles) frame.delete();
        timelapseFrameFiles.clear();
        shutterButton.setContentDescription("Stop timelapse");
        captureTimelapseFrame();
    }

    private void captureTimelapseFrame() {
        if (!timelapseRunning || timelapseCaptureInFlight) return;
        imageCapture.setTargetRotation(targetRotation);
        timelapseCaptureInFlight = true;
        int before = timelapseFrames;
        File temp = new File(getCacheDir(), newName("LAPSE", ".jpg"));
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(temp).build(),
                ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                        timelapseCaptureInFlight = false;
                        timelapseFrames = before + 1;
                        saveFile(temp, "image/jpeg");
                        timelapseFrameFiles.add(temp);
                        setStatus("Timelapse · " + timelapseFrames + " frames");
                        if (timelapseRunning) {
                            int delay = prefs().getInt(KEY_LAPSE, 5);
                            handler.postDelayed(timelapseCaptureRunnable, delay * 1000L);
                        } else finalizeTimelapse();
                    }
                    @Override public void onError(@NonNull ImageCaptureException error) {
                        timelapseCaptureInFlight = false;
                        temp.delete();
                        setStatus("Timelapse frame failed");
                        if (timelapseRunning) handler.postDelayed(timelapseCaptureRunnable, 1000);
                        else finalizeTimelapse();
                    }
                });
    }

    private void stopTimelapse() {
        timelapseRunning = false;
        handler.removeCallbacks(timelapseCaptureRunnable);
        if (shutterTimer != null) shutterTimer.cancel();
        shutterButton.setContentDescription("Start timelapse");
        if (timelapseCaptureInFlight) setStatus("Finishing final frame…");
        else finalizeTimelapse();
    }

    private void finalizeTimelapse() {
        if (timelapseFinalizing) return;
        if (timelapseFrameFiles.isEmpty()) {
            shutterButton.setEnabled(true);
            setStatus("Timelapse stopped · no frames captured");
            return;
        }
        timelapseFinalizing = true;
        shutterButton.setEnabled(false);
        int frameCount = timelapseFrameFiles.size();
        int fps = prefs().getInt(KEY_LAPSE_FPS, 24);
        ArrayList<File> frames = new ArrayList<>(timelapseFrameFiles);
        File video = new File(getCacheDir(), newName("TIMELAPSE", ".mp4"));
        setStatus("Creating " + fps + " FPS timelapse video…");
        backgroundExecutor.execute(() -> {
            Exception failure = null;
            try {
                TimelapseVideoEncoder.encode(frames, video, fps);
            } catch (Exception error) {
                failure = error;
                Log.e("PaperCamera", "Timelapse video encoding failed", error);
            }
            Exception result = failure;
            handler.post(() -> {
                boolean videoSaved = result == null && video.length() > 0 && saveFile(video, "video/mp4");
                if (videoSaved) video.delete();
                for (File frame : frames) frame.delete();
                timelapseFrameFiles.clear();
                timelapseFinalizing = false;
                shutterButton.setEnabled(true);
                if (videoSaved) {
                    setStatus("Saved · " + frameCount + " JPEGs + " + fps + " FPS video");
                } else {
                    setStatus("JPEGs saved · timelapse video failed");
                }
            });
        });
    }

    private void saveCompletedCapture(File temp, String mime, boolean verifyThumbnail) {
        boolean saved = saveFile(temp, mime);
        if (!saved) setStatus("Save failed · capture retained temporarily");
        else if (verifyThumbnail) {
            boolean hasThumbnail = false;
            try { hasThumbnail = new ExifInterface(temp).hasThumbnail(); } catch (IOException ignored) {}
            String result = hasThumbnail ? "Saved · EXIF thumbnail verified" : "Saved · no EXIF thumbnail found";
            setStatus(lastSaveUsedFallback ? result + " · default folder used" : result);
        } else setStatus(lastSaveUsedFallback ? "Saved · default folder used" : "Saved");
        if (saved) temp.delete();
        shutterButton.setEnabled(true);
    }

    private boolean saveFile(File source, String mime) {
        lastSaveUsedFallback = false;
        String treeValue = prefs().getString(KEY_TREE, null);
        if (treeValue != null && saveToSelectedFolder(source, mime, Uri.parse(treeValue))) return true;
        if (treeValue != null) lastSaveUsedFallback = true;
        return saveToMediaStore(source, mime);
    }

    private boolean saveToSelectedFolder(File source, String mime, Uri treeUri) {
        DocumentFile created = null;
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(this, treeUri);
            created = folder == null ? null : folder.createFile(mime, source.getName());
            if (created == null) return false;
            long copied = 0;
            ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(created.getUri(), "rwt");
            if (descriptor == null) throw new IOException("No writable file descriptor");
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
            }
            if (copied != source.length() || copied == 0) throw new IOException("Incomplete copy");
            return true;
        } catch (Exception error) {
            Log.e("PaperCamera", "Selected-folder save failed", error);
            if (created != null) created.delete();
            return false;
        }
    }

    private boolean saveToMediaStore(File source, String mime) {
        Uri destination = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    mime.startsWith("video") ? "Movies/PaperCamera" : "Pictures/PaperCamera");
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = mime.startsWith("video") ?
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            destination = getContentResolver().insert(collection, values);
            if (destination == null) return false;
            long copied = 0;
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IOException("No MediaStore output stream");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
            }
            if (copied != source.length() || copied == 0) throw new IOException("Incomplete MediaStore copy");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues publish = new ContentValues();
                publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(destination, publish, null, null);
            }
            return true;
        } catch (Exception error) {
            Log.e("PaperCamera", "MediaStore save failed", error);
            if (destination != null) getContentResolver().delete(destination, null, null);
            return false;
        }
    }

    private void showSettings() {
        internalDialogVisible = true;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.paper));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.top_rule);
        Button close = reviewTextButton("×");
        close.setTextSize(30);
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = label("Settings");
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundResource(R.drawable.top_rule);
        String[] names = {"General settings", "Photo settings", "Video settings", "Timelapse settings"};
        int[] icons = {R.drawable.ic_settings, R.drawable.ic_photo,
                R.drawable.ic_video, R.drawable.ic_timelapse};
        ImageButton[] tabButtons = new ImageButton[names.length];
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int[] selected = {0};
        for (int i = 0; i < names.length; i++) {
            ImageButton tab = settingsTabButton(icons[i], names[i]);
            tab.setTag(i);
            tabButtons[i] = tab;
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(48), 1));
            tab.setOnClickListener(v -> {
                selected[0] = (int) v.getTag();
                styleSettingsTabs(tabButtons, selected[0]);
                populateSettingsPage(content, selected[0], dialog);
            });
        }
        page.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) dialog.dismiss();
            return true;
        });
        dialog.setOnDismissListener(d -> {
            internalDialogVisible = false;
            updateTimerUi();
            if (mode != Mode.VIDEO && !timelapseRunning && !timelapseFinalizing) startCamera();
        });
        dialog.setContentView(page);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        styleSettingsTabs(tabButtons, selected[0]);
        populateSettingsPage(content, selected[0], dialog);
    }

    private void populateSettingsPage(LinearLayout content, int page, Dialog dialog) {
        SharedPreferences p = prefs();
        content.removeAllViews();
        content.setPadding(dp(20), dp(2), dp(20), dp(18));
        if (page == 0) {
            content.addView(sectionTitle("General"));
            content.addView(checkSetting("Review before save", KEY_REVIEW, false));
            content.addView(checkSetting("Volume buttons control shutter", KEY_VOLUME_SHUTTER, true));
            content.addView(sectionTitle("Gallery grid"));
            content.addView(note("Rows per page"));
            content.addView(radioSetting(KEY_GALLERY_ROWS,
                    new String[]{"2", "3", "4"}, new int[]{2, 3, 4},
                    p.getInt(KEY_GALLERY_ROWS, 3)));
            content.addView(note("Columns per page"));
            content.addView(radioSetting(KEY_GALLERY_COLUMNS,
                    new String[]{"2", "3", "4"}, new int[]{2, 3, 4},
                    p.getInt(KEY_GALLERY_COLUMNS, 3)));
            content.addView(sectionTitle("Save location"));
            TextView location = note(p.getString(KEY_TREE, null) == null ?
                    "Pictures/PaperCamera and Movies/PaperCamera" : "Selected folder (photos and videos)");
            content.addView(location);
            Button choose = settingsButton("CHOOSE FOLDER");
            choose.setOnClickListener(v -> {
                dialog.dismiss();
                folderPicker.launch(null);
            });
            content.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            Button useDefault = settingsButton("USE DEFAULT FOLDERS");
            useDefault.setOnClickListener(v -> {
                p.edit().remove(KEY_TREE).apply();
                location.setText("Pictures/PaperCamera and Movies/PaperCamera");
                setStatus("Using default save folders");
            });
            content.addView(useDefault, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        } else if (page == 1) {
            content.addView(sectionTitle("Photo"));
            content.addView(checkSetting("Embed EXIF thumbnail", KEY_THUMB, true));
            content.addView(note("The embedded thumbnail improves browsing on devices such as the Canon SELPHY."));
        } else if (page == 2) {
            content.addView(sectionTitle("Video"));
            content.addView(checkSetting("Record audio", KEY_VIDEO_AUDIO, true));
            content.addView(note("Turn this off for silent video without microphone permission."));
        } else {
            content.addView(sectionTitle("Timelapse"));
            content.addView(note("Each captured JPEG is saved, followed by an MP4 made from the same frames."));
            content.addView(sectionTitle("Capture interval (sec)"));
            content.addView(customNumberSetting(KEY_LAPSE, KEY_LAPSE_CUSTOM,
                    new String[]{"2", "5", "10"},
                    new int[]{2, 5, 10}, p.getInt(KEY_LAPSE, 5),
                    15, 1, 86400, "Seconds (1–86400)"));
            content.addView(sectionTitle("Video playback rate (FPS)"));
            content.addView(customNumberSetting(KEY_LAPSE_FPS, KEY_LAPSE_FPS_CUSTOM,
                    new String[]{"12", "24", "30"},
                    new int[]{12, 24, 30}, p.getInt(KEY_LAPSE_FPS, 24),
                    25, 1, 60, "FPS (1–60)"));
        }
    }

    private CheckBox checkSetting(String text, String key, boolean defaultValue) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(ContextCompat.getColor(this, R.color.ink));
        box.setTextSize(16);
        box.setMinHeight(dp(44));
        box.setChecked(prefs().getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) -> prefs().edit().putBoolean(key, checked).apply());
        return box;
    }

    private RadioGroup radioSetting(String key, String[] labels, int[] values, int selectedValue) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int checkedId = View.NO_ID;
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(values[i]);
            option.setText(labels[i]);
            option.setTextColor(ContextCompat.getColor(this, R.color.ink));
            option.setTextSize(15);
            option.setMinHeight(dp(44));
            group.addView(option, new RadioGroup.LayoutParams(0, dp(44), 1f));
            if (values[i] == selectedValue) checkedId = option.getId();
        }
        if (checkedId != View.NO_ID) group.check(checkedId);
        group.setOnCheckedChangeListener((radioGroup, id) -> {
            RadioButton checked = radioGroup.findViewById(id);
            if (checked == null) return;
            prefs().edit().putInt(key, (int) checked.getTag()).apply();
            if (KEY_TIMER.equals(key)) updateTimerUi();
        });
        return group;
    }

    private View customNumberSetting(String key, String customKey, String[] labels, int[] values,
                                     int selectedValue, int customDefault, int min, int max,
                                     String entryHint) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int checkedId = View.NO_ID;
        boolean presetSelected = false;
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(values[i]);
            option.setText(labels[i]);
            option.setTextColor(ContextCompat.getColor(this, R.color.ink));
            option.setTextSize(15);
            option.setMinHeight(dp(44));
            group.addView(option, new RadioGroup.LayoutParams(0, dp(44), 1f));
            if (values[i] == selectedValue) {
                checkedId = option.getId();
                presetSelected = true;
            }
        }

        RadioButton custom = new RadioButton(this);
        custom.setId(View.generateViewId());
        custom.setText("Custom");
        custom.setTextColor(ContextCompat.getColor(this, R.color.ink));
        custom.setTextSize(15);
        custom.setMinHeight(dp(44));
        group.addView(custom, new RadioGroup.LayoutParams(0, dp(44), 1.35f));
        if (!presetSelected) checkedId = custom.getId();

        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setInputType(InputType.TYPE_CLASS_NUMBER);
        entry.setTextColor(ContextCompat.getColor(this, R.color.ink));
        entry.setHintTextColor(ContextCompat.getColor(this, R.color.mid_ink));
        entry.setTextSize(15);
        entry.setHint(entryHint);
        entry.setContentDescription("Custom " + entryHint);
        entry.setSelectAllOnFocus(true);
        int remembered = prefs().getInt(customKey,
                presetSelected ? customDefault : Math.max(min, Math.min(max, selectedValue)));
        remembered = Math.max(min, Math.min(max, remembered));
        entry.setText(String.valueOf(remembered));
        entry.setVisibility(presetSelected ? View.GONE : View.VISIBLE);

        group.check(checkedId);
        group.setOnCheckedChangeListener((radioGroup, id) -> {
            if (id == custom.getId()) {
                entry.setVisibility(View.VISIBLE);
                int value = validNumber(entry.getText().toString(), min, max,
                        prefs().getInt(customKey, customDefault));
                prefs().edit().putInt(key, value).putInt(customKey, value).apply();
            } else {
                RadioButton checked = radioGroup.findViewById(id);
                if (checked == null || !(checked.getTag() instanceof Integer)) return;
                entry.setVisibility(View.GONE);
                prefs().edit().putInt(key, (int) checked.getTag()).apply();
            }
        });
        entry.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (group.getCheckedRadioButtonId() != custom.getId()) return;
                try {
                    int value = Integer.parseInt(text.toString());
                    if (value >= min && value <= max) {
                        prefs().edit().putInt(key, value).putInt(customKey, value).apply();
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        wrapper.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        wrapper.addView(entry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return wrapper;
    }

    private int validNumber(String text, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(text)));
        } catch (NumberFormatException ignored) {
            return Math.max(min, Math.min(max, fallback));
        }
    }

    private void styleSettingsTabs(ImageButton[] tabs, int selected) {
        for (int i = 0; i < tabs.length; i++) {
            boolean active = i == selected;
            tabs[i].setBackgroundColor(ContextCompat.getColor(this, active ? R.color.ink : R.color.paper));
            tabs[i].setImageTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, active ? R.color.paper : R.color.ink)));
        }
    }

    private ImageButton settingsTabButton(int icon, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        return button;
    }

    private Button settingsButton(String text) {
        Button button = reviewTextButton(text);
        button.setTextSize(13);
        button.setBackgroundResource(R.drawable.top_rule);
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.BLACK);
        view.setTextSize(17);
        view.setPadding(0, 14, 0, 4);
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = label(text);
        view.setTextSize(18);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private TextView note(String text) {
        TextView view = label(text);
        view.setTextSize(14);
        view.setTextColor(ContextCompat.getColor(this, R.color.mid_ink));
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private Size findRearCameraThumbnailSize() throws Exception {
        CameraManager manager = getSystemService(CameraManager.class);
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            Integer facing = info.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            Size[] sizes = info.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES);
            Size best = new Size(0, 0);
            if (sizes != null) for (Size size : sizes) {
                long pixels = (long) size.getWidth() * size.getHeight();
                long bestPixels = (long) best.getWidth() * best.getHeight();
                if (pixels > bestPixels && pixels <= 512L * 512L) best = size;
            }
            return best;
        }
        return new Size(0, 0);
    }

    private String newName(String prefix, String suffix) {
        return prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + suffix;
    }

    private void setStatus(String text) {
        handler.removeCallbacks(hideStatusRunnable);
        if (text.startsWith("Starting camera")) {
            statusView.setVisibility(View.GONE);
            loadingView.setVisibility(View.VISIBLE);
            return;
        }
        if (text.startsWith("Ready")) {
            statusView.setVisibility(View.GONE);
            loadingView.setVisibility(View.GONE);
            return;
        }
        loadingView.setVisibility(View.GONE);
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        String lower = text.toLowerCase(Locale.US);
        boolean persistent = text.startsWith("Timer ·") || text.startsWith("Recording") ||
                text.startsWith("Timelapse ·") || text.startsWith("Creating") ||
                text.startsWith("Finishing") || lower.contains("error") ||
                lower.contains("failed") || lower.contains("required");
        if (!persistent) handler.postDelayed(hideStatusRunnable, 3000);
    }

    @Override protected void onDestroy() {
        if (recording != null) recording.stop();
        timelapseRunning = false;
        handler.removeCallbacksAndMessages(null);
        backgroundExecutor.shutdown();
        if (orientationListener != null) orientationListener.disable();
        super.onDestroy();
    }
}
