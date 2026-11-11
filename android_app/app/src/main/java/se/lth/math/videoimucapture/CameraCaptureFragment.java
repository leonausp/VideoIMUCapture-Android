package se.lth.math.videoimucapture;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import se.lth.math.videoimucapture.gles.FullFrameRect;
import se.lth.math.videoimucapture.gles.Texture2dProgram;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.content.Context.WINDOW_SERVICE;

public class CameraCaptureFragment extends Fragment
        implements SurfaceTexture.OnFrameAvailableListener, TextureMovieEncoder.EncoderListener {

    public static final String TAG = "VIMUC-CaptureFragment";
    private static final boolean VERBOSE = false;
    private SampleGLView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private TextView mCaptureResultText;
    private AspectFrameLayout mAspectFrameLayout;

    private boolean mRecordingEnabled;      // controls button state
    private int mRecordStartPress = 0;      // which press a held first frame belongs to

    /** Whether the video recorder is running, for anything outside that needs to stop it. */
    public boolean isRecording() {
        return mRecordingEnabled;
    }

    private FloatingActionButton mRecordingButton;
    private FloatingActionButton mWarningButton;
    private FloatingActionButton mCaptureButton;
    private FloatingActionButton mTorchButton;
    private TextView mCaptureStatusText;
    private TextView[] mModeViews;

    private int mCameraPreviewWidth, mCameraPreviewHeight;

    // CAMERA SLEEP. The phone cooks while the app merely sits open, because the preview keeps
    // the sensor streaming at full rate and the ISP awake so recording can start instantly.
    // After `idle_sleep_s` seconds without a touch or a run, the repeating request is stopped:
    // the preview freezes, the sensor goes quiet, and any touch brings it back in about half a
    // second — the honest price of not cooking. Never while recording or during a stills run.
    private final Handler mIdleHandler =
            new Handler(Looper.getMainLooper());
    private boolean mCameraAsleep = false;
    private final Runnable mIdleSleep = this::sleepCamera;
    private long mRecordStartMs = 0;    // elapsedRealtime at record start, for the on-screen clock
    private BlurBudgetController mBlurBudget = null;   // #38; created on demand, default off
    private volatile boolean mHoldStill = false;      // shown in the readout when moving too fast
    private volatile float mSmearPx = -1f;            // predicted motion smear of the current frame

    //Owned by the activity
    private CameraCaptureActivity.CameraHandler getmCameraHandler() {
        return ((CameraCaptureActivity) getActivity()).getmCameraHandler();
    };
    private TextureMovieEncoder getsVideoEncoder() {
        return ((CameraCaptureActivity) getActivity()).getsVideoEncoder();
    };
    private RecordingWriter getsRecordingWriter() {
        return ((CameraCaptureActivity) getActivity()).getsRecordingWriter();
    };
    private IMUManager getmImuManager() {
        return ((CameraCaptureActivity) getActivity()).getmImuManager();
    };
    private Camera2Proxy getmCamera2Proxy() {
        return ((CameraCaptureActivity) getActivity()).getmCamera2Proxy();
    };
    private CameraSettingsManager getmCameraSettingsManager() {
        return ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
    };

    private String renewOutputDir() {
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        String folderName = dateFormat.format(new Date());

        String dir = ((CameraCaptureActivity) getActivity()).getResultRoot();

        String outputDir = dir + File.separator + folderName;
        (new File(outputDir)).mkdirs();
        return outputDir;
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate: " + this);

        mRecordingEnabled = getsVideoEncoder().isRecording();
        getsVideoEncoder().setEncoderListener(this);

        Log.d(TAG, "onCreate complete: " + this);
    }

    // View initialization logic
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: " + this);
        return inflater.inflate(R.layout.capture_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: " + this);

        // Hook to aspectframe
        mAspectFrameLayout = view.findViewById(R.id.cameraPreview_afl);

        // Setup buttons
        mRecordingButton = view.findViewById(R.id.toggleRecording_button);
        mRecordingButton.setOnClickListener(this::clickToggleRecording);

        mWarningButton = view.findViewById(R.id.OIS_warning_button);
        mWarningButton.setOnClickListener(this::clickWarning);

        mCaptureButton = view.findViewById(R.id.capture_button);
        mCaptureStatusText = view.findViewById(R.id.captureStatus_text);
        if (!BuildConfig.STILLS_UI) {
            // VIO build: video only. The stills button (with its caption), the gap beside it and
            // the WALK/OBJECT/PANO strip are hidden; the record button stays centred on its own.
            ((View) mCaptureButton.getParent()).setVisibility(View.GONE);
            view.findViewById(R.id.capture_controls_space).setVisibility(View.GONE);
            view.findViewById(R.id.captureMode_strip).setVisibility(View.GONE);
        }
        mModeViews = new TextView[]{
                view.findViewById(R.id.mode_walk),
                view.findViewById(R.id.mode_object),
                view.findViewById(R.id.mode_pano),
        };
        final CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        mCaptureButton.setOnClickListener(v -> {
            wakeCamera();
            modes.onCaptureButton();
        });
        CaptureModeManager.Mode[] all = CaptureModeManager.Mode.values();
        for (int i = 0; i < mModeViews.length; i++) {
            final CaptureModeManager.Mode m = all[i];
            mModeViews[i].setOnClickListener(v -> {
                if (modes.isRunning()) {
                    return;   // a run owns the mode until it ends
                }
                modes.setMode(m);
                highlightMode(modes.getMode());
            });
        }
        modes.setStateListener(this::onCaptureRunState);
        highlightMode(modes.getMode());

        mTorchButton = view.findViewById(R.id.torch_button);
        if (!BuildConfig.TORCH_UI) {
            mTorchButton.setVisibility(View.GONE);
        }
        setUpExposureControls(view);
        view.findViewById(R.id.gallery_button).setOnClickListener(v -> {
            if (getActivity() != null) {
                ((CameraCaptureActivity) getActivity()).showRoll(null);
            }
        });
        mTorchButton.setOnClickListener(v -> {
            Camera2Proxy proxy = getmCamera2Proxy();
            if (proxy == null || !proxy.torchAvailable()) {
                return;
            }
            proxy.setTorch(!proxy.isTorchOn());
            updateTorchButton(proxy.isTorchOn());
        });

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        mGLView = view.findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(
                getmCameraHandler(), getsVideoEncoder());
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGLView.setTouchListener((event, width, height) -> {
            boolean wasAsleep = mCameraAsleep;
            wakeCamera();
            if (!wasAsleep && getmCameraHandler() != null) {
                getmCameraHandler().changeManualFocusPoint(
                        event.getX(), event.getY(), width, height);
            }
        });

        mCaptureResultText = view.findViewById(R.id.captureResult_text);

    }

    private int idleSleepSeconds() {
        if (getActivity() == null) {
            return 0;
        }
        return PreferenceManager.getDefaultSharedPreferences(getActivity())
                .getInt("idle_sleep_s", RecommendedDefaults.IDLE_SLEEP_S);
    }

    private void armIdleTimer() {
        mIdleHandler.removeCallbacks(mIdleSleep);
        int s = idleSleepSeconds();
        if (s > 0) {
            mIdleHandler.postDelayed(mIdleSleep, s * 1000L);
        }
    }

    private void cancelIdleTimer() {
        mIdleHandler.removeCallbacks(mIdleSleep);
    }

    private void sleepCamera() {
        CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
        if (act == null) {
            return;
        }
        CaptureModeManager modes = act.getmCaptureModeManager();
        if (mRecordingEnabled || (modes != null && modes.isRunning())) {
            armIdleTimer();          // busy after all; ask again later
            return;
        }
        Camera2Proxy proxy = act.getmCamera2Proxy();
        if (proxy == null) {
            return;
        }
        proxy.stopPreview();
        mCameraAsleep = true;
        if (mCaptureStatusText != null) {
            mCaptureStatusText.setText("camera asleep — tap to wake");
        }
        Log.i(TAG, "camera asleep after " + idleSleepSeconds() + " s idle");
    }

    private void wakeCamera() {
        if (mCameraAsleep) {
            CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
            Camera2Proxy proxy = act == null ? null : act.getmCamera2Proxy();
            if (proxy != null) {
                proxy.startPreview();
            }
            mCameraAsleep = false;
            if (mCaptureStatusText != null) {
                mCaptureStatusText.setText("");
            }
            Log.i(TAG, "camera awake");
        }
        armIdleTimer();
    }

    private void updateTorchButton(boolean on) {
        if (mTorchButton == null) {
            return;
        }
        mTorchButton.setBackgroundTintList(
                ColorStateList.valueOf(
                        getResources().getColor(
                                on ? R.color.torchOnBkg : R.color.torchOffBkg, null)));
        // PANO records the environment's own light; a torch riding the camera records
        // the operator's instead. Say so once rather than silently allowing it.
        if (on && ((CameraCaptureActivity) getActivity()).getmCaptureModeManager()
                .getMode() == CaptureModeManager.Mode.PANO && mCaptureStatusText != null) {
            mCaptureStatusText.setText("torch lights YOUR light, not the scene's");
        }
    }

    /**
     * Put the mode strip back in step with the mode manager, for the test matrix: a cell can
     * set the mode itself, and a strip still highlighting the operator's choice would be the
     * app disagreeing with itself in the one place the operator is looking.
     */
    public void refreshModeHighlight() {
        CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        if (modes != null) {
            highlightMode(modes.getMode());
        }
    }

    private void highlightMode(CaptureModeManager.Mode mode) {
        if (mModeViews == null) {
            return;
        }
        CaptureModeManager.Mode[] all = CaptureModeManager.Mode.values();
        for (int i = 0; i < mModeViews.length; i++) {
            boolean on = all[i] == mode;
            mModeViews[i].setAlpha(on ? 1.0f : 0.45f);
            mModeViews[i].setTypeface(null, on ? Typeface.BOLD
                    : Typeface.NORMAL);
        }
    }

    /**
     * Called on the main thread by CaptureModeManager as a run starts and stops.
     *
     * The summary goes to its OWN line. It used to be written over the mode label, which
     * left "5 shots" sitting where the mode should be after a run ended — the state
     * readout permanently replaced by the last thing that happened to it.
     */
    private void onCaptureRunState(RunState state) {
        if (mCaptureButton != null) {
            // The icon says what THIS button does, not whether the phone is busy. During a
            // plain video clip the capture button starts a stills run, so it must not wear a
            // stop icon: the record button is the one that stops the video.
            mCaptureButton.setImageResource(state.stillsRunning
                    ? R.drawable.ic_capture_stop : R.drawable.ic_capture_still);
            mCaptureButton.setBackgroundTintList(
                    ColorStateList.valueOf(
                            getResources().getColor(state.stillsRunning
                                    ? R.color.captureButtonActiveBkg
                                    : R.color.captureButtonBkg, null)));
            // A composite is a sequence, and a second press during it used to start a second
            // one. The press is refused underneath as well; this is so the button admits it.
            mCaptureButton.setEnabled(!state.compositeRunning);
            mCaptureButton.setAlpha(state.compositeRunning ? 0.4f : 1.0f);
        }
        // The idle timer and the mode strip care about whether ANYTHING is going on, which is
        // a different question from whether a stills run is.
        if (state.anyActive) {
            cancelIdleTimer();
        } else {
            armIdleTimer();
        }
        if (mCaptureStatusText != null) {
            mCaptureStatusText.setText(state.summary == null ? "" : state.summary);
            if (!state.anyActive) {
                // Clear the finished-run summary after a beat so the strip is the only
                // persistent state on screen.
                mCaptureStatusText.postDelayed(() -> {
                    if (mCaptureStatusText != null) {
                        mCaptureStatusText.setText("");
                    }
                }, 4000L);
            }
        }
        // Dim the strip while anything owns the mode, so it reads as unavailable. A video
        // recording owns it just as a stills run does -- the mode decides the discipline the
        // clip is being shot under, and changing it mid-clip would make the directory name a
        // lie about what the frames were.
        if (mModeViews != null) {
            for (TextView v : mModeViews) {
                v.setEnabled(!state.anyActive);
            }
        }
    }


    // updates mCameraPreviewWidth/Height
    public void setLayoutAspectRatio(Size cameraPreviewSize) {
        if (mAspectFrameLayout == null) {
            return;
        }
        Display display = ((WindowManager) getActivity().getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        mCameraPreviewWidth = cameraPreviewSize.getWidth();
        mCameraPreviewHeight = cameraPreviewSize.getHeight();
        if (display.getRotation() == Surface.ROTATION_0) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if (display.getRotation() == Surface.ROTATION_180) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        ((CameraCaptureActivity) getActivity()).initializeCamera();
        Log.d(TAG, "Keeping screen on for previewing recording.");
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateControls();
        mCameraAsleep = false;   // the camera is re-initialised on resume; the timer restarts
        armIdleTimer();

        mGLView.onResume();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight, getmCamera2Proxy().getSwappedDimensions());
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    public void onPause() {
        super.onPause();
        cancelIdleTimer();
        mCameraAsleep = false;

        if (mRecordingEnabled) {
            stopRecording();
        }

        ((CameraCaptureActivity) getActivity()).releaseCamera();
        Log.d(TAG, "onPause -- Pause Camera preview");
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.notifyStopPreview();
            }
        });
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        mGLView.onPause();

    }

    //Callback from encoder when it is finished and thread is shutting down.
    public void onEncodingFinished() {
        Log.d(TAG, "Got Encoder listener call");
        mRecordingEnabled = false;
        getActivity().runOnUiThread(() -> updateControls());
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        wakeCamera();
        mRecordingEnabled = !mRecordingEnabled;
        if (mRecordingEnabled) {
            cancelIdleTimer();
            mRecordStartMs = SystemClock.elapsedRealtime();
            startRecording();
            updateControls();
        } else {
            mRecordStartMs = 0;
            // disable button until recording finish
            if (mRecordingButton != null){
                mRecordingButton.setEnabled(false);
            }
            stopRecording();
            armIdleTimer();
        }
    }

    public void clickWarning(View view) {
        //Display warning dialog
        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();

        Bundle args = new Bundle();
        args.putInt("title", R.string.warning_dialog_title);

        StringBuilder builder = new StringBuilder();
        if (cameraSettingsManager.OISEnabled()) {
            builder.append("- ");
            if (cameraSettingsManager.OISDataEnabled())
                builder.append(getResources().getString(R.string.warning_text_ois_with_data));
            else
                builder.append(getResources().getString(R.string.warning_text_ois_no_data));
            builder.append("\n\n");
        }
        if (cameraSettingsManager.DVSEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_dvs));
        }
        if (cameraSettingsManager.DistortionCorrectionEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_distortion));
        }
        if (!getmImuManager().sensorsExist()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_imu_missing));
        }

        args.putString("message", builder.toString());

        InfoDialogFragment newFragment = new InfoDialogFragment();
        newFragment.setArguments(args);
        newFragment.show(getActivity().getSupportFragmentManager(), "warning");
    }

    private void enableWarning(Boolean enable) {
        if (mWarningButton == null) {
            return;
        }
        if (enable) {
            Animation anim = new AlphaAnimation(0.8f, 1.0f);
            anim.setDuration(500); //Manage the blinking time with this parameter
            anim.setStartOffset(20);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(Animation.INFINITE);
            mWarningButton.setAnimation(anim);
            mWarningButton.setVisibility(View.VISIBLE);
        } else {
            mWarningButton.clearAnimation();
            mWarningButton.setVisibility(View.GONE);
        }
    }

    private void startRecording() {
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        // The mode owns the session: it names the directory, locks the auto algorithms so
        // the clip has one radiometry, and decides whether this is a new session or a join
        // onto a stills run that is already streaming.
        File dir = modes.beginVideoSession();
        if (dir == null) {
            mRecordingEnabled = false;
            updateControls();
            return;
        }
        String outputDir = dir.getAbsolutePath();
        String outputFile = outputDir + File.separator + "video_recording.mp4";
        String metaFile = outputDir + File.separator + "video_meta.pb3";
        RecordingWriter recordingWriter = getsRecordingWriter();
        // Only open the writer if nothing else already has it. Calling startRecording on a
        // live writer would put a second header over the first and orphan everything the
        // stills run had already queued.
        boolean writerWasIdle = !recordingWriter.isRecording();
        if (writerWasIdle) {
            try {
                recordingWriter.startRecording(metaFile);
            } catch (IOException e) {
                throw new RuntimeException("Could not start meta data recording:" + e);
            }
        }

        mRenderer.resetOutputFiles(outputFile, recordingWriter); // this will not cause sync issues
        if (writerWasIdle) {
            ((CameraCaptureActivity) getActivity()).startSensorStreams(recordingWriter);
        }
        if (camera2Proxy == null) {
            throw new RuntimeException("mCamera2Proxy should not be null upon toggling record button");
        }

        // The pictures and their rows start together, and normally at once. When this press had
        // to end a pair warm-up they start a moment later, once the restored preview is what the
        // sensor is actually delivering -- otherwise the clip opens on warm-up frames and the
        // swap lands inside it (M3f, 2026-09-21: four such frames, then a three-row hole). The
        // rows wait with the pictures: a result row written during the hold has no frame to
        // join, and would only move the loss from one counter to the other.
        final long holdMs = modes.videoStartHoldMs();
        if (holdMs <= 0) {
            startFrames(camera2Proxy, modes, dir, recordingWriter);
        } else {
            Log.i(TAG, "holding the first frame " + holdMs + " ms for the restored preview");
            final int press = ++mRecordStartPress;
            mGLView.postDelayed(() -> {
                // Stopped again inside the hold, or stopped and restarted: not this press's
                // frames to start.
                if (mRecordingEnabled && press == mRecordStartPress) {
                    startFrames(camera2Proxy, modes, dir, recordingWriter);
                }
            }, holdMs);
        }
    }

    /** The second half of startRecording: frame rows, periodic pairs, blur budget, encoder. */
    private void startFrames(Camera2Proxy camera2Proxy, CaptureModeManager modes, File dir,
                             RecordingWriter recordingWriter) {
        camera2Proxy.startRecordingCaptureResult(recordingWriter);
        // Codec and bitrate are read here, on the UI thread, and handed to the renderer before
        // the state change is queued, so the GL thread sees them when it builds the encoder.
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(getActivity());

        // Periodic stereo pairs inside the video (ReconStab #36). Opt-in, like the blur budget:
        // it puts a second sensor into the recording's own request for the whole clip, and what
        // that does to the video is unmeasured until the S1/S2 cells have been shot.
        // Hidden with the stills UI: a value stored earlier must not keep a second sensor running.
        int stereoEvery = BuildConfig.STILLS_UI ? prefs.getInt("stereo_interval_s", RecommendedDefaults.STEREO_INTERVAL_S) : 0;
        if (stereoEvery > 0) {
            StillCaptureManager.CaptureMode cm;
            switch (modes.getMode()) {
                case OBJECT: cm = StillCaptureManager.CaptureMode.OBJECT; break;
                case PANO: cm = StillCaptureManager.CaptureMode.PANO; break;
                default: cm = StillCaptureManager.CaptureMode.WALK;
            }
            camera2Proxy.startPeriodicStereo(stereoEvery * 1000L, dir, recordingWriter, cm);
        }
        mRenderer.setEncoderPrefs(prefs.getString("video_codec", RecommendedDefaults.VIDEO_CODEC),
                prefs.getInt("video_bitrate_mbps", RecommendedDefaults.VIDEO_BITRATE_MBPS) * 1_000_000);

        // Blur budget (#38): only when explicitly enabled AND exposure is allowed to float —
        // a locked radiometry has nothing for it to do. Default off, so this is normally skipped.
        boolean budgetOn = prefs.getBoolean("blur_budget", RecommendedDefaults.BLUR_BUDGET)
                && !prefs.getBoolean("lock_radiometry", RecommendedDefaults.LOCK_RADIOMETRY);
        if (budgetOn && camera2Proxy != null) {
            if (mBlurBudget == null) {
                mBlurBudget = new BlurBudgetController(getmImuManager(), camera2Proxy,
                        (holdStill, smearPx, capNs) -> {
                            mHoldStill = holdStill;
                            mSmearPx = smearPx;
                        });
            }
            mBlurBudget.start(prefs.getInt("blur_budget_px", RecommendedDefaults.BLUR_BUDGET_PX),
                    prefs.getInt("hold_still_px", RecommendedDefaults.HOLD_STILL_PX),
                    prefs.getBoolean("blur_budget_manual", RecommendedDefaults.BLUR_BUDGET_MANUAL));
        }

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(true);
            }
        });
    }

    private void stopRecording() {
        Log.d(TAG, "Stop recording");
        if (mBlurBudget != null) {
            mBlurBudget.stop();
        }
        mHoldStill = false;
        mSmearPx = -1f;
        CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        if (camera2Proxy != null) {
            // Pairs first, while the writer is still open for their last rows -- but only if
            // the video is the last stream on this session. Since the pairs became a property
            // of the RUN rather than of the recording, a stills walk can own them, and taking
            // the physical streams back out here would end the walk's metric anchor at the
            // moment the operator stopped the video and kept walking.
            if (!modes.isRunning()) {
                camera2Proxy.stopPeriodicStereo();
            }
            camera2Proxy.stopRecordingCaptureResult();
        }
        // Tear down the sensor streams only if nothing else is using them. The test was
        // "did the video own this session?", which is not the same question: a stills run
        // that opened the session and then STOPPED while the video kept going leaves the
        // streams open and unowned, and this branch would skip them forever. What matters is
        // whether a stills run is live right now -- stopping its IMU here would blind the
        // stillness trigger mid-walk, and not stopping it when nothing is left leaks a
        // sensor stream into the next session.
        boolean stillsRunning = modes.isRunning();
        modes.endVideoSession();
        if (!stillsRunning) {
            ((CameraCaptureActivity) getActivity()).stopSensorStreams();
        }

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(false);
            }
        });
        // ...and the writer last, for the same reason: a stills run still walking is still
        // writing IMU, GNSS and still rows into it. Closing it here because the VIDEO stopped
        // would seal the file in the middle of a run that has not finished.
        if (!stillsRunning) {
            getsRecordingWriter().stopRecording();
        }
    }


    /**
     * The live readout under the toolbar, as labelled lines:
     *   1. which lens (focal length in mm, so main vs. ultrawide is obvious) and the focal length
     *      in VIDEO pixels: the HAL's per-frame value where it applies, else the estimate (≈);
     *   2. exposure, ISO, IMU rate;
     *   3. status, only when there is something to say (recording clock, storage, EV, blur,
     *      heat from 40 C or when throttling, battery).
     * It used to be one '|'-joined line ("|FL: 2794.394|Exp: ...") with the focal length in
     * full-sensor pixels, which read as "IFL" and ran into the torch button.
     */
    public void updateCaptureResultPanel(
            final Float focalMm,
            final float focalVideoPx,
            final boolean focalMeasured,
            final Long exposureTimeNs,
            final Integer iso) {
        // f: the phone's per-frame value in video pixels when available ("f 913 px"), else the
        // app's estimate, marked with ≈ ("f ≈892 px"). One value: fx = fy on square pixels.
        final String lens = (focalMm == null ? "Lens ?" :
                String.format(Locale.getDefault(), "Lens %.2f mm", focalMm))
                + (Float.isNaN(focalVideoPx) || focalVideoPx <= 0f ? "" :
                String.format(Locale.getDefault(), focalMeasured ? "  ·  f %.0f px"
                        : "  ·  f ≈%.0f px", focalVideoPx));
        // Shutter priority, before recording: the camera runs auto exposure as a LIGHT METER
        // (e.g. 20 ms / ISO 644), which is not what gets recorded. Show what RECORD will lock
        // instead (5 ms / ISO 2575, the same brightness), as the exposure buttons do.
        Long showExpNs = exposureTimeNs;
        Integer showIso = iso;
        if (!mRecordingEnabled && "SHUTTER_PRIORITY".equals(exposureMode())) {
            Camera2Proxy proxy = getmCamera2Proxy();
            ShutterPriority.Result r = proxy != null ? proxy.shutterPriorityPreview() : null;
            if (r != null) {
                showExpNs = r.exposureNs;
                showIso = r.iso;
            }
        }
        final String sexpotime = showExpNs == null ? "Exp ?" :
                String.format(Locale.getDefault(), "Exp %.2f ms", showExpNs / 1000000.0);
        final String siso = showIso == null ? "" : String.format(Locale.getDefault(), "  ·  ISO %d", showIso);
        final String imuHz = String.format(Locale.getDefault(), "  ·  IMU %.0f Hz",
                getmImuManager().getSensorFrequency());
        // THE CLOCK. The operator could not see how long a take had run. Refreshed with every
        // capture result, so it costs nothing extra; the battery temperature rides along because
        // a five-minute walk is exactly when it starts to matter.
        String clock = "";
        if (mRecordingEnabled && mRecordStartMs > 0) {
            long s = (SystemClock.elapsedRealtime() - mRecordStartMs) / 1000;
            clock = String.format(Locale.getDefault(), "REC %02d:%02d", s / 60, s % 60);
        }
        String heat = "";
        CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
        if (act != null && act.getmThermalLogger() != null) {
            float c = act.getmThermalLogger().lastBatteryTempC();
            // Only when it matters: from 40 C, or whenever the OS is throttling.
            if (!Float.isNaN(c) && c >= 40f) {
                heat = String.format(Locale.getDefault(), "Phone %.0f °C", c);
            }
            // A temperature is a number the operator has to interpret; THROTTLING is a fact
            // they can act on. The status is what the OS itself is acting on, and until now
            // it went into the file and nowhere else -- so a walk degrading under heat looked
            // exactly like a walk that was going fine.
            if (act.getmThermalLogger().isThrottling()) {
                heat = "THROTTLING" + (Float.isNaN(c) ? "" : String.format(Locale.getDefault(), " %.0f °C", c));
            }
        }
        // The measured smear, always, when the budget is running. The alarm is the exception;
        // the number is the information, and it is what lets the operator learn where their own
        // threshold belongs instead of being nagged by a constant.
        final String hold = (mHoldStill && mRecordingEnabled) ? "HOLD STILL" : "";
        final String smear = (mSmearPx >= 0f && mRecordingEnabled)
                ? String.format(Locale.getDefault(), "Smear %.0f px", mSmearPx) : "";
        // Exposure compensation, but only when the operator has actually moved it (#28). At 0 it
        // is noise on a line that is already dense; away from 0 it is the reason the frames look
        // the way they do, and it must be visible without opening settings.
        String ev = "";
        // Periodic pairs (#36): the count, so the operator can see them being taken and a
        // clip whose count stopped moving is caught on site rather than at the desk.
        String pairs = "";
        if (act != null && act.getmCamera2Proxy() != null) {
            float stops = act.getmCamera2Proxy().getExposureCompensationStops();
            if (Math.abs(stops) > 0.01f) {
                ev = String.format(Locale.getDefault(), "EV %+.1f", stops);
            }
            if (mRecordingEnabled && act.getmCamera2Proxy().periodicStereoActive()) {
                pairs = String.format(Locale.getDefault(), "Pairs %d",
                        act.getmCamera2Proxy().periodicStereoPairs());
            }
        }
        // HOW MUCH LONGER THIS CAN GO ON. Free space in GB is a number the operator has to do
        // arithmetic on, mid-walk, to get the only thing they actually want to know. The guard
        // measures what this session is really consuming, so the readout can say it in minutes
        // -- and minutes are what decides whether to turn back now or keep going.
        String room = "";
        if (act != null && act.getStorageGuard() != null
                && act.getStorageGuard().isWatching()) {
            StorageGuard.Status st = act.getStorageGuard().status();
            if (st != null && st.secondsLeft >= 0) {
                room = st.secondsLeft < 60
                        ? String.format(Locale.getDefault(), "Storage %d s left", st.secondsLeft)
                        : String.format(Locale.getDefault(), "Storage %d min left", st.secondsLeft / 60);
            }
        }
        // Charge, but only once it is worth the room on the line: above 20% the phone's own
        // status bar is right there, and a readout that shows everything all the time is one
        // the operator stops reading.
        String batt = "";
        if (act != null && act.getBatteryGuard() != null
                && act.getBatteryGuard().isWatching()) {
            BatteryGuard.Status bs = act.getBatteryGuard().status();
            if (bs != null && (bs.low || bs.critical || bs.charging)) {
                batt = BatteryGuard.readout(bs);
            }
        }
        StringBuilder status = new StringBuilder();
        for (String part : new String[]{clock, hold, smear, heat, batt, room, pairs, ev}) {
            String t = part == null ? "" : part.replace("|", "").trim();
            if (!t.isEmpty()) {
                if (status.length() > 0) {
                    status.append("  ·  ");
                }
                status.append(t);
            }
        }
        final String line = lens + "\n" + sexpotime + siso + imuHz
                + (status.length() > 0 ? "\n" + status : "");

        getActivity().runOnUiThread(() -> {
            if (mCaptureResultText != null) {
                mCaptureResultText.setText(line);
                refreshExposureControls();
            }
        });
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    // ------------------------------------------------------------------ exposure on the preview
    //
    // Two buttons above RECORD (exposure time, ISO) and an adjust bar (- / value / + / AUTO).
    // They write the same preferences as the settings screen ("exposure", "iso",
    // "exposure_mode") and apply them to the preview at once. Locked while recording: an exposure
    // change mid-clip would change the data. ISO + / - means MANUAL; AUTO means shutter priority;
    // changing the exposure time in an automatic mode switches to shutter priority.

    private com.google.android.material.button.MaterialButton mExposureChip, mIsoChip;
    private View mExposurePanel;
    private TextView mPanelValue;
    private android.widget.Button mPanelAuto;
    private String mPanelParam = null;   // "exp", "iso" or null (closed)

    private void setUpExposureControls(View view) {
        mExposureChip = view.findViewById(R.id.exposure_chip);
        mIsoChip = view.findViewById(R.id.iso_chip);
        mExposurePanel = view.findViewById(R.id.exposure_panel);
        mPanelValue = view.findViewById(R.id.panel_value);
        mPanelAuto = view.findViewById(R.id.panel_auto);
        mExposureChip.setOnClickListener(v -> togglePanel("exp"));
        mIsoChip.setOnClickListener(v -> togglePanel("iso"));
        view.findViewById(R.id.panel_minus).setOnClickListener(v -> stepPanel(-1));
        view.findViewById(R.id.panel_plus).setOnClickListener(v -> stepPanel(+1));
        mPanelAuto.setOnClickListener(v -> {
            prefs().edit().putString("exposure_mode", "SHUTTER_PRIORITY").apply();
            applyExposure();
        });
        mPanelValue.setOnClickListener(v -> typePanelValue());
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(requireContext());
    }

    private void togglePanel(String param) {
        if (mRecordingEnabled) {
            android.widget.Toast.makeText(getActivity(), "Exposure is locked while recording",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        mPanelParam = param.equals(mPanelParam) ? null : param;
        refreshExposureControls();
    }

    /** Current mode name as stored ("MANUAL", "SHUTTER_PRIORITY", "TOUCH_AUTO", ...). */
    private String exposureMode() {
        return prefs().getString("exposure_mode", RecommendedDefaults.EXPOSURE_MODE);
    }

    private SliderSteps exposureSteps() {
        CameraSettingsManager m = getmCameraSettingsManager();
        android.util.Range<Float> r = m == null ? null : m.getExposureRangeMs();
        return r == null ? null : SliderSteps.ofValues(r.getLower(), r.getUpper(), CameraValueSteps.SHUTTER_MS);
    }

    private SliderSteps isoSteps() {
        CameraSettingsManager m = getmCameraSettingsManager();
        android.util.Range<Integer> r = m == null ? null : m.getIsoRange();
        return r == null ? null : SliderSteps.ofValues(r.getLower(), r.getUpper(), CameraValueSteps.ISO);
    }

    /** The ISO the user sees now: manual value, or what shutter priority would use. */
    private int currentIso() {
        Camera2Proxy proxy = getActivity() != null ? getmCamera2Proxy() : null;
        ShutterPriority.Result r = proxy != null ? proxy.shutterPriorityPreview() : null;
        if ("SHUTTER_PRIORITY".equals(exposureMode()) && r != null) {
            return r.iso;
        }
        return prefs().getInt("iso", RecommendedDefaults.ISO);
    }

    private void stepPanel(int dir) {
        if ("exp".equals(mPanelParam)) {
            SliderSteps s = exposureSteps();
            if (s == null) {
                return;
            }
            float now = prefs().getFloat("exposure", RecommendedDefaults.EXPOSURE_MS);
            setExposureMs(s.stepFrom(now, dir));
        } else if ("iso".equals(mPanelParam)) {
            SliderSteps s = isoSteps();
            if (s == null) {
                return;
            }
            setManualIso(Math.round(s.stepFrom(currentIso(), dir)));
        }
    }

    private void setExposureMs(float ms) {
        SharedPreferences.Editor e = prefs().edit().putFloat("exposure", ms);
        String mode = exposureMode();
        if (!"MANUAL".equals(mode) && !"SHUTTER_PRIORITY".equals(mode)) {
            e.putString("exposure_mode", "SHUTTER_PRIORITY");
        }
        e.apply();
        applyExposure();
    }

    private void setManualIso(int iso) {
        prefs().edit().putInt("iso", iso).putString("exposure_mode", "MANUAL").apply();
        applyExposure();
    }

    private void applyExposure() {
        Camera2Proxy proxy = getActivity() != null ? getmCamera2Proxy() : null;
        if (proxy != null) {
            proxy.applySettingsNow();
        }
        refreshExposureControls();
    }

    private void typePanelValue() {
        if (mPanelParam == null || getActivity() == null) {
            return;
        }
        boolean exp = "exp".equals(mPanelParam);
        CameraSettingsManager m = getmCameraSettingsManager();
        android.util.Range<Float> er = m == null ? null : m.getExposureRangeMs();
        android.util.Range<Integer> ir = m == null ? null : m.getIsoRange();
        if ((exp && er == null) || (!exp && ir == null)) {
            return;   // camera not configured yet, or no manual exposure on this camera
        }
        float min = exp ? er.getLower() : ir.getLower();
        float max = exp ? er.getUpper() : ir.getUpper();
        android.widget.EditText input = new android.widget.EditText(getActivity());
        input.setInputType(exp
                ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                : android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(exp ? String.format(Locale.US, "%.2f",
                prefs().getFloat("exposure", RecommendedDefaults.EXPOSURE_MS))
                : String.valueOf(currentIso()));
        input.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle(exp ? "Exposure time (ms)" : "ISO")
                .setMessage(String.format(Locale.US, exp ? "Range %.3f – %.1f ms" : "Range %.0f – %.0f",
                        min, max))
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    float v;
                    try {
                        v = Float.parseFloat(input.getText().toString().trim().replace(',', '.'));
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    v = Math.max(min, Math.min(max, v));
                    if (exp) {
                        setExposureMs(v);
                    } else {
                        setManualIso(Math.round(v));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        input.requestFocus();
    }

    /** Button and bar texts from the stored settings and the live metering. UI thread. */
    private void refreshExposureControls() {
        if (mExposureChip == null || getActivity() == null) {
            return;
        }
        String mode = exposureMode();
        boolean manual = "MANUAL".equals(mode);
        boolean sp = "SHUTTER_PRIORITY".equals(mode);
        float ms = prefs().getFloat("exposure", RecommendedDefaults.EXPOSURE_MS);
        String expText = (manual || sp) ? formatMsShort(ms) : "Exp auto";
        String isoText;
        Camera2Proxy proxy = getmCamera2Proxy();
        ShutterPriority.Result r = (sp && proxy != null) ? proxy.shutterPriorityPreview() : null;
        if (manual) {
            isoText = "ISO " + prefs().getInt("iso", RecommendedDefaults.ISO);
        } else if (sp && r != null) {
            isoText = "ISO auto " + r.iso;
            if (r.shortened) {
                expText = formatMsShort(r.exposureNs / 1e6f) + " ☀";
            } else if (r.underexposedStops > 0.05) {
                isoText += String.format(Locale.US, "  ▼%.1f", r.underexposedStops);
            }
        } else if (sp) {
            isoText = mRecordingEnabled ? "ISO locked" : "ISO auto";
        } else {
            isoText = "ISO auto";
        }
        mExposureChip.setText(expText);
        mIsoChip.setText(isoText);
        boolean enabled = !mRecordingEnabled;
        mExposureChip.setAlpha(enabled ? 1f : 0.5f);
        mIsoChip.setAlpha(enabled ? 1f : 0.5f);
        if (!enabled) {
            mPanelParam = null;
        }
        mExposurePanel.setVisibility(mPanelParam == null ? View.GONE : View.VISIBLE);
        if ("exp".equals(mPanelParam)) {
            mPanelValue.setText(formatMs(ms));
            mPanelAuto.setVisibility(View.GONE);
        } else if ("iso".equals(mPanelParam)) {
            mPanelValue.setText(sp ? "auto " + currentIso() : String.valueOf(currentIso()));
            mPanelAuto.setVisibility(View.VISIBLE);
            mPanelAuto.setAlpha(sp ? 1f : 0.6f);
        }
    }

    /** Compact form for the stacked corner buttons: "5.00 ms". */
    private static String formatMsShort(float ms) {
        return ms >= 10 ? String.format(Locale.US, "%.1f ms", ms) : String.format(Locale.US, "%.2f ms", ms);
    }

    /** "5.0 ms", plus the shutter fraction where it is a round one ("1/200 s"). */
    private static String formatMs(float ms) {
        String s = ms >= 10 ? String.format(Locale.US, "%.1f ms", ms) : String.format(Locale.US, "%.2f ms", ms);
        if (ms > 0 && ms < 1000) {
            float denom = 1000f / ms;
            if (Math.abs(denom - Math.round(denom)) < 0.02f * denom) {
                s += "  (1/" + Math.round(denom) + " s)";
            }
        }
        return s;
    }

    public void updateControls() {
        refreshExposureControls();
        if (mRecordingButton != null) {
            int id = mRecordingEnabled ?
                    R.drawable.ic_stop_record : R.drawable.ic_start_record;
            Log.d(TAG, "DRAWING: " + id);
            mRecordingButton.setImageResource(id);
            mRecordingButton.setEnabled(true);
        }

        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();
        if ((cameraSettingsManager == null) || !cameraSettingsManager.isInitialized()) {
            // Camera settings not ready yet, may be due to slow start or waiting for camera permission. Post delayed call.
            getmCameraHandler().sendMessageDelayed(
                    getmCameraHandler().obtainMessage(CameraCaptureActivity.CameraHandler.MSG_UPDATE_WARNING),
                    200
            );
            enableWarning(false);
        } else {
            // We have camera settings, update warning accordingly. The warning means "a
            // setting is on that makes this capture something other than a measurement", so
            // IMU batching belongs in it: batched delivery is free for a recorder and is not
            // free for the stillness shutter, which reads the stream live to decide when a
            // walk fires a still. A quiet moment seen 100 ms late is a still fired 14 cm
            // further down the walk, and nothing in the resulting file would look wrong.
            boolean batching = PreferenceManager
                    .getDefaultSharedPreferences(getActivity()).getInt("imu_batch_ms", RecommendedDefaults.IMU_BATCH_MS) > 0;
            enableWarning(cameraSettingsManager.OISEnabled()
                    || cameraSettingsManager.DVSEnabled()
                    || cameraSettingsManager.DistortionCorrectionEnabled()
                    || batching
                    || !getmImuManager().sensorsExist());
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLView.requestRender();

        // The camera's own frame rate (sensor frame duration), not the encoder's estimate:
        // that one starts at a placeholder 15 and only updates while recording, so the screen
        // said "@15 FPS" before every recording although the sensor ran at 30.
        // Frames can still arrive while the fragment is detaching: no activity, no proxy.
        Camera2Proxy proxy = getActivity() != null ? getmCamera2Proxy() : null;
        float fps = proxy != null ? proxy.getSensorFrameRate() : Float.NaN;
        if (Float.isNaN(fps)) {
            fps = getsVideoEncoder().mFrameRate;
        }
        final String sfps = String.format(Locale.getDefault(), "%.1f FPS", fps);
        String previewFacts = mCameraPreviewWidth + "x" + mCameraPreviewHeight + "@" + sfps;

        View fragmentView = getView();

        if (fragmentView != null) {
            TextView text = (TextView) fragmentView.findViewById(R.id.cameraParams_text);
            text.setText(previewFacts);
        }

    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    public void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        Camera2Proxy camera2Proxy = getmCamera2Proxy();

        if (camera2Proxy != null) {
            camera2Proxy.setPreviewSurfaceTexture(st);
            camera2Proxy.openCamera();
        } else {
            throw new RuntimeException(
                    "Try to set surface texture while camera2proxy is null");
        }
    }

    public void handleDisableSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(null);
    }

}

/**
 * Renderer object for our GLSurfaceView.
 * <p>
 * Do not call any methods here directly from another thread -- use the
 * GLSurfaceView#queueEvent() call.
 */
class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = CameraCaptureActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private CameraCaptureActivity.CameraHandler mCameraHandler;
    private TextureMovieEncoder mVideoEncoder;
    private String mOutputFile;
    private RecordingWriter mMetadataRecorder;
    // Set from the UI thread before the recording state change is queued; read on the GL
    // thread when the encoder is built. A bitrate of 0 means the BPP formula in CameraUtils.
    private volatile String mEncoderMime = VideoEncoderCore.DEFAULT_MIME_TYPE;
    private volatile int mEncoderBitRate = 0;

    private FullFrameRect mFullScreen;

    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    private SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private int mFrameCount;

    // width/height of the incoming camera preview frames
    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;
    private boolean mSwappedVideoDimensions;


    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     *
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder  video encoder object
     */
    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler,
                                 TextureMovieEncoder movieEncoder) {
        mCameraHandler = cameraHandler;
        mVideoEncoder = movieEncoder;
        mTextureId = -1;

        mRecordingStatus = -1;
        mRecordingEnabled = false;
        mFrameCount = -1;

        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void resetOutputFiles(String outputFile, RecordingWriter metaRecorder) {
        mOutputFile = outputFile;
        mMetadataRecorder = metaRecorder;
    }

    public void setEncoderPrefs(String mimeType, int bitRate) {
        mEncoderMime = (mimeType == null || mimeType.isEmpty())
                ? VideoEncoderCore.DEFAULT_MIME_TYPE : mimeType;
        mEncoderBitRate = Math.max(0, bitRate);
    }

    /**
     * The automatic bitrate, codec-aware. Measured 2026-09-01 on the S24U: the first HEVC clip
     * came out the same size as the H.264 one (343.7 MB, 93.8 Mbit/s), because both encoders
     * were handed the BPP formula's 93.6 Mbit/s. At equal bitrate HEVC buys quality, not disk;
     * the disk comes from asking it for less. 0.55 is the middle of the 40-52% saving the codec
     * is known for at equal visual quality, and it is a starting point to be measured against
     * feature counts, not a fact about this scene.
     */
    private int autoBitRate(int width, int height) {
        int avc = CameraUtils.calcBitRate(width, height, VideoEncoderCore.FRAME_RATE);
        if (VideoEncoderCore.HEVC_MIME_TYPE.equals(mEncoderMime)) {
            return (int) (avc * 0.55f);
        }
        return avc;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     */
    public void notifyPausing() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
            mFullScreen = null;             //  to be destroyed
        }
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void notifyStopPreview() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "Stop preview");
            mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                    CameraCaptureActivity.CameraHandler.MSG_DISABLE_SURFACE_TEXTURE, mSurfaceTexture));
        }
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     */
    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        mRecordingEnabled = isRecording;
        //Extra state update, in case no frame comes. May happen when recording stops.
        updateState();
    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     */
    public void setCameraPreviewSize(int width, int height, boolean swappedDimensions) {
        Log.d(TAG, "setCameraPreviewSize");
        mIncomingWidth = width;
        mIncomingHeight = height;
        mSwappedVideoDimensions = swappedDimensions;
        mIncomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        mTextureId = mFullScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        // Tell the UI thread to enable the camera preview.
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        mSurfaceTexture.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        updateState();

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        mVideoEncoder.setTextureId(mTextureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }

        if (mIncomingSizeUpdated) {
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(mTextureId, mSTMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        showBox = (mRecordingStatus == RECORDING_ON);
        if (showBox && (++mFrameCount & 0x04) == 0) {
            drawBox();
        }
    }

    void updateState() {
        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    mVideoEncoder.startRecording(
                            new TextureMovieEncoder.EncoderConfig(
                                    mOutputFile,
                                    mSwappedVideoDimensions ? mIncomingHeight : mIncomingWidth,
                                    mSwappedVideoDimensions ? mIncomingWidth : mIncomingHeight,
                                    mEncoderBitRate > 0 ? mEncoderBitRate
                                            : autoBitRate(mIncomingWidth, mIncomingHeight),
                                    mEncoderMime,
                                    EGL14.eglGetCurrentContext(),
                                    mMetadataRecorder));
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }
    }

    /**
     * Draws a red box in the corner.
     */
    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}