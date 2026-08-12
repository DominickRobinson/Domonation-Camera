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
import android.widget.TextView;

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
import androidx.camera.core.MirrorMode;
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

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
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

    private static final String PREFS = "domonation_camera";
    private static final String KEY_TREE = "save_tree";
    private static final String KEY_THUMB = "exif_thumbnail";
    private static final String KEY_REVIEW = "review_before_save";
    private static final String KEY_TIMER = "timer_seconds";
    private static final String KEY_TIMER_ENABLED = "timer_enabled";
    private static final String KEY_VIDEO_AUDIO = "video_audio";
    private static final String KEY_VOLUME_SHUTTER = "volume_shutter";
    private static final String KEY_FLIP_FRONT_CAMERA = "flip_front_camera";
    private static final String KEY_LAPSE = "timelapse_seconds";
    private static final String KEY_LAPSE_FPS = "timelapse_fps";
    private static final String KEY_AUDIO_ASKED = "audio_asked";

    private PreviewView previewView;
    private MmdLoadingView loadingView;
    private MmdFabView shutterButton;
    private ImageButton flashButton;
    private MmdFabView modeButton;
    private ImageButton timerButton;
    private ImageButton zoomToggleButton;
    private ImageButton exposureToggleButton;
    private ImageButton settingsButton;
    private MmdFabView galleryButton;
    private TextView timerBadge;
    private View captureControls;
    private View zoomControls;
    private View exposureControls;
    private MmdSliderView exposureBar;
    private MmdSliderView zoomBar;
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
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ArrayList<File> timelapseFrameFiles = new ArrayList<>();
    private final Runnable timelapseCaptureRunnable = this::captureTimelapseFrame;
    private boolean timelapseRunning;
    private boolean timelapseCaptureInFlight;
    private boolean timelapseFinalizing;
    private final Runnable restoreGalleryIconRunnable = () -> {
        if (galleryButton != null) {
            galleryButton.setImageResource(R.drawable.ic_gallery);
            galleryButton.setContentDescription("Open gallery");
        }
    };
    private boolean internalDialogVisible;
    private Dialog startupDialog;
    private MediaStorage mediaStorage;
    private CountDownTimer shutterTimer;
    private int targetRotation = Surface.ROTATION_0;
    private OrientationEventListener orientationListener;
    private final ArrayList<View> orientationViews = new ArrayList<>();

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
            });

    private final ActivityResultLauncher<String> audioPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                startVideo();
            });

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs().edit().putString(KEY_TREE, uri.toString()).apply();
            });

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppTheme.wrap(base));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppTheme.applySystemBars(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setWindowAnimations(0);
        View content = MmdUi.setContent(this, R.layout.activity_main);
        showStartupBranding();
        mediaStorage = new MediaStorage(this);

        previewView = content.findViewById(R.id.preview);
        loadingView = content.findViewById(R.id.camera_loading);
        shutterButton = content.findViewById(R.id.shutter);
        flashButton = content.findViewById(R.id.flash);
        modeButton = content.findViewById(R.id.mode);
        timerButton = content.findViewById(R.id.timer_toggle);
        timerBadge = content.findViewById(R.id.timer_badge);
        zoomToggleButton = content.findViewById(R.id.zoom_toggle);
        exposureToggleButton = content.findViewById(R.id.exposure_toggle);
        settingsButton = content.findViewById(R.id.settings);
        galleryButton = content.findViewById(R.id.gallery);
        shutterButton.setPrimary(true);
        galleryButton.setRoundedSquare(true);
        captureControls = content.findViewById(R.id.capture_controls);
        zoomControls = content.findViewById(R.id.zoom_controls);
        exposureControls = content.findViewById(R.id.exposure_controls);
        exposureBar = content.findViewById(R.id.exposure);
        zoomBar = content.findViewById(R.id.zoom_bar);
        exposureLabel = content.findViewById(R.id.exposure_label);
        zoomTicks = content.findViewById(R.id.zoom_ticks);
        exposureTicks = content.findViewById(R.id.exposure_ticks);
        int[] rotatingIds = {R.id.timer_toggle, R.id.timer_badge, R.id.zoom_toggle,
                R.id.exposure_toggle, R.id.settings, R.id.mode, R.id.shutter, R.id.flash, R.id.gallery,
                R.id.zoom_out, R.id.zoom_label, R.id.zoom_in, R.id.exposure_down,
                R.id.exposure_label, R.id.exposure_up, R.id.camera_loading};
        for (int id : rotatingIds) orientationViews.add(content.findViewById(id));
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        zoomLabel = content.findViewById(R.id.zoom_label);
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
                if (imageCapture != null) imageCapture.setTargetRotation(targetRotation);
                if (videoCapture != null) videoCapture.setTargetRotation(targetRotation);
                applyUiRotation(surfaceRotationDegrees(next));
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        applyUiRotation(surfaceRotationDegrees(targetRotation));

        modeButton.setOnClickListener(v -> setMode(
                mode == Mode.PHOTO ? Mode.VIDEO : mode == Mode.VIDEO ? Mode.TIMELAPSE : Mode.PHOTO));
        settingsButton.setOnClickListener(v -> showSettings());
        timerButton.setOnClickListener(v -> cycleTimer());
        zoomToggleButton.setOnClickListener(v -> setControlPanel(
                controlPanel == ControlPanel.ZOOM ? ControlPanel.NONE : ControlPanel.ZOOM));
        exposureToggleButton.setOnClickListener(v -> setControlPanel(
                controlPanel == ControlPanel.EXPOSURE ? ControlPanel.NONE : ControlPanel.EXPOSURE));
        flashButton.setOnClickListener(v -> cycleFlash());
        galleryButton.setOnClickListener(v ->
                startActivity(new Intent(this, GalleryActivity.class)));
        shutterButton.setOnClickListener(v -> onShutter());
        previewView.setOnTouchListener(this::handlePreviewTouch);
        content.findViewById(R.id.zoom_out).setOnClickListener(v -> stepZoom(false));
        content.findViewById(R.id.zoom_in).setOnClickListener(v -> stepZoom(true));
        content.findViewById(R.id.exposure_down).setOnClickListener(v -> stepExposure(false));
        content.findViewById(R.id.exposure_up).setOnClickListener(v -> stepExposure(true));
        exposureBar.setOnProgressChangedListener((progress, fromUser) -> {
            if (fromUser) setExposure(progress);
        });
        zoomBar.setOnProgressChangedListener((progress, fromUser) -> {
                if (!fromUser || updatingZoomBar || camera == null ||
                        camera.getCameraInfo().getZoomState().getValue() == null) return;
                androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                float fraction = progress / (float) Math.max(1, zoomBar.getMax());
                setZoomRatio(state.getMinZoomRatio() +
                        fraction * (state.getMaxZoomRatio() - state.getMinZoomRatio()));
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

    private void showStartupBranding() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.getWindow();
        LinearLayout page = new LinearLayout(this);
        page.setGravity(android.view.Gravity.CENTER);
        page.setBackgroundColor(Color.WHITE);
        ImageView branding = new ImageView(this);
        branding.setImageResource(R.drawable.domonation_splash);
        branding.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        page.addView(branding, new LinearLayout.LayoutParams(dp(360), dp(360)));
        dialog.setCancelable(false);
        dialog.setContentView(page);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(0);
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        startupDialog = dialog;
        handler.postDelayed(() -> {
            if (startupDialog == dialog) startupDialog = null;
            if (dialog.isShowing()) dialog.dismiss();
        }, 1800);
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
        button.setBackgroundColor(active ?
                ContextCompat.getColor(this, R.color.ink) : Color.TRANSPARENT);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, active ? R.color.paper : R.color.ink)));
    }

    private void startCamera() {
        loadingView.setVisibility(View.VISIBLE);
        shutterButton.setEnabled(false);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        Executor main = ContextCompat.getMainExecutor(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception ignored) { loadingView.setVisibility(View.GONE); }
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
            videoCapture = new VideoCapture.Builder<>(recorder)
                    .setMirrorMode(prefs().getBoolean(KEY_FLIP_FRONT_CAMERA, true) ?
                            MirrorMode.MIRROR_MODE_ON_FRONT_ONLY : MirrorMode.MIRROR_MODE_OFF)
                    .build();
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
        loadingView.setVisibility(View.GONE);
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
        if (shutterTimer != null) {
            shutterTimer.cancel();
            shutterTimer = null;
            shutterButton.setEnabled(true);
            updateTimerUi();
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
                    timerBadge.setText(String.valueOf(remaining));
                    timerBadge.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onFinish() {
                shutterTimer = null;
                updateTimerUi();
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
                else saveCompletedCapture(temp, "image/jpeg");
            }
            @Override public void onError(@NonNull ImageCaptureException error) {
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
                () -> saveCompletedCapture(temp, "image/jpeg"),
                () -> {
                    temp.delete();
                    shutterButton.setEnabled(true);
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
        shutterButton.setActive(true);
        recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
                recording = null;
                setRecordingControlsVisible(true);
                setControlPanel(ControlPanel.NONE);
                shutterButton.setActive(false);
                shutterButton.setContentDescription("Start video");
                if (done.hasError()) {
                    temp.delete();
                } else if (prefs().getBoolean(KEY_REVIEW, false)) showVideoReview(temp);
                else saveCompletedCapture(temp, "video/mp4");
            }
        });
        setRecordingControlsVisible(false);
    }

    private void setRecordingControlsVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.INVISIBLE;
        modeButton.setVisibility(visibility);
        galleryButton.setVisibility(visibility);
        timerButton.setVisibility(visibility);
        timerBadge.setVisibility(visible && prefs().getBoolean(KEY_TIMER_ENABLED, false) ?
                View.VISIBLE : View.GONE);
        settingsButton.setVisibility(visibility);
        zoomToggleButton.setVisibility(View.VISIBLE);
        exposureToggleButton.setVisibility(View.VISIBLE);
        flashButton.setVisibility(View.VISIBLE);
    }

    private void startTimelapse() {
        if (imageCapture == null) return;
        timelapseRunning = true;
        timelapseFinalizing = false;
        timelapseCaptureInFlight = false;
        for (File frame : timelapseFrameFiles) frame.delete();
        timelapseFrameFiles.clear();
        shutterButton.setActive(true);
        shutterButton.setContentDescription("Stop timelapse");
        captureTimelapseFrame();
    }

    private void captureTimelapseFrame() {
        if (!timelapseRunning || timelapseCaptureInFlight || imageCapture == null) return;
        imageCapture.setTargetRotation(targetRotation);
        timelapseCaptureInFlight = true;
        File frame = new File(getCacheDir(), newName("LAPSE", ".jpg"));
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(frame).build(),
                ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                        timelapseCaptureInFlight = false;
                        timelapseFrameFiles.add(frame);
                        if (timelapseRunning) {
                            int delay = prefs().getInt(KEY_LAPSE, 5);
                            handler.postDelayed(timelapseCaptureRunnable, delay * 1000L);
                        } else finalizeTimelapse();
                    }

                    @Override public void onError(@NonNull ImageCaptureException error) {
                        timelapseCaptureInFlight = false;
                        frame.delete();
                        if (timelapseRunning) handler.postDelayed(timelapseCaptureRunnable, 1000L);
                        else finalizeTimelapse();
                    }
                });
    }

    private void stopTimelapse() {
        timelapseRunning = false;
        handler.removeCallbacks(timelapseCaptureRunnable);
        shutterButton.setActive(false);
        shutterButton.setContentDescription("Start timelapse");
        if (!timelapseCaptureInFlight) finalizeTimelapse();
    }

    private void finalizeTimelapse() {
        if (timelapseFinalizing) return;
        if (timelapseFrameFiles.isEmpty()) {
            shutterButton.setEnabled(true);
            return;
        }
        timelapseFinalizing = true;
        shutterButton.setEnabled(false);
        int fps = prefs().getInt(KEY_LAPSE_FPS, 24);
        ArrayList<File> frames = new ArrayList<>(timelapseFrameFiles);
        File video = new File(getCacheDir(), newName("TIMELAPSE", ".mp4"));
        backgroundExecutor.execute(() -> {
            boolean encoded = false;
            try {
                TimelapseVideoEncoder.encode(frames, video, fps);
                encoded = video.length() > 0;
            } catch (Exception error) {
                Log.e("DomonationCamera", "Timelapse video encoding failed", error);
            }
            boolean ready = encoded;
            handler.post(() -> {
                for (File frame : frames) frame.delete();
                timelapseFrameFiles.clear();
                timelapseFinalizing = false;
                shutterButton.setEnabled(true);
                if (!ready) {
                    video.delete();
                } else if (prefs().getBoolean(KEY_REVIEW, false)) {
                    showVideoReview(video);
                } else {
                    saveCompletedCapture(video, "video/mp4");
                }
            });
        });
    }

    private void showVideoReview(File temp) {
        VideoPlayerView video = new VideoPlayerView(this);
        video.setVideo(Uri.fromFile(temp), true);
        showFullScreenReview("Review video", video,
                () -> saveCompletedCapture(temp, "video/mp4"),
                () -> {
                    temp.delete();
                    shutterButton.setEnabled(true);
                });
    }

    private void showFullScreenReview(String title, View media, Runnable save, Runnable discard) {
        internalDialogVisible = true;
        Runnable stopMedia = () -> {
            if (media instanceof VideoPlayerView) ((VideoPlayerView) media).release();
        };
        MmdReviewDialog.show(this, title, media,
                () -> { stopMedia.run(); save.run(); },
                () -> { stopMedia.run(); discard.run(); },
                () -> internalDialogVisible = false);
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

    private void saveCompletedCapture(File temp, String mime) {
        boolean saved = saveFile(temp, mime);
        if (saved && mime.startsWith("image/")) showGallerySavedCheck();
        if (saved) temp.delete();
        shutterButton.setEnabled(true);
    }

    private boolean saveFile(File source, String mime) {
        String treeValue = prefs().getString(KEY_TREE, null);
        MediaStorage.SaveResult result = mediaStorage.save(source, mime,
                treeValue == null ? null : Uri.parse(treeValue));
        return result.saved;
    }

    private void showSettings() {
        internalDialogVisible = true;
        new SettingsDialogController(this, prefs(), new SettingsDialogController.Host() {
             public void chooseFolder() { folderPicker.launch(null); }
             public void onSettingsDismissed() {
                internalDialogVisible = false;
                updateTimerUi();
                if (recording == null) startCamera();
            }
        }).show();
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

    private void showGallerySavedCheck() {
        handler.removeCallbacks(restoreGalleryIconRunnable);
        galleryButton.setImageResource(R.drawable.ic_check);
        galleryButton.setContentDescription("Photo saved");
        handler.postDelayed(restoreGalleryIconRunnable, 1200);
    }

    @Override protected void onDestroy() {
        if (startupDialog != null && startupDialog.isShowing()) startupDialog.dismiss();
        if (recording != null) recording.stop();
        timelapseRunning = false;
        handler.removeCallbacksAndMessages(null);
        backgroundExecutor.shutdown();
        if (orientationListener != null) orientationListener.disable();
        super.onDestroy();
    }
}
