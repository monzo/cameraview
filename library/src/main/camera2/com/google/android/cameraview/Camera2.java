/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;


@SuppressWarnings("MissingPermission")
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class Camera2 extends CameraViewImpl {

    private static final String TAG = "Camera2";

    private static final SparseIntArray INTERNAL_FACINGS = new SparseIntArray();

    static {
        INTERNAL_FACINGS.put(Constants.FACING_BACK, CameraCharacteristics.LENS_FACING_BACK);
        INTERNAL_FACINGS.put(Constants.FACING_FRONT, CameraCharacteristics.LENS_FACING_FRONT);
    }

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    private final CameraManager mCameraManager;

    private MediaRecorder mMediaRecorder;

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mSurfaceInfo.configure(surface, width, height);
            configureTransform();
            startCaptureSession();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            mSurfaceInfo.configure(surface, width, height);
            configureTransform();
            startCaptureSession();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            mSurfaceInfo.configure(null, 0, 0);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }

    };

    private final CameraDevice.StateCallback mCameraDeviceCallback
            = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCamera = camera;
            mCallback.onCameraOpened();
            startCaptureSession();
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            mCallback.onCameraClosed();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            mCamera = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "onError: " + camera.getId() + " (" + error + ")");
            camera.close();
            mCamera = null;
        }

    };

    private final CameraCaptureSession.StateCallback mSessionCallback
            = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (mCamera == null) {
                return;
            }
            mCaptureSession = session;
            updateAutoFocus();
            updateFlash();

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (mStartVideoRecording) {
                        mStartVideoRecording = false;
                        mMediaRecorder.start();
                        mRecording = true;
                    }
                    try {
                        mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, null);
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "Failed to start camera preview.", e);
                    }
                }
            });
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Failed to configure capture session.");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (mCaptureSession != null && mCaptureSession.equals(session)) {
                mCaptureSession = null;
            }
        }

    };

    private PictureCaptureCallback mCaptureCallback = new PictureCaptureCallback() {

        @Override
        public void onPrecaptureRequired() {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                                       CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            setState(STATE_PRECAPTURE);
            try {
                mCaptureSession.capture(mPreviewRequestBuilder.build(), this, null);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                                           CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to run precapture sequence.", e);
            }
        }

        @Override
        public void onReady() {
            captureStillPicture();
        }


        @Override
        public void onLockFocusRetryRequired() {
            lockFocus();
        }

    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            try (Image image = reader.acquireNextImage()) {
                Image.Plane[] planes = image.getPlanes();
                if (planes.length > 0) {
                    ByteBuffer buffer = planes[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    mCallback.onPictureTaken(data);
                }
            }
        }

    };


    private String mCameraId;

    private CameraCharacteristics mCameraCharacteristics;

    private CameraDevice mCamera;

    private CameraCaptureSession mCaptureSession;

    private CaptureRequest.Builder mPreviewRequestBuilder;

    private ImageReader mImageReader;

    private final SurfaceInfo mSurfaceInfo = new SurfaceInfo();

    private final SizeMap mPreviewSizes = new SizeMap();

    private final SizeMap mOutputSizes = new SizeMap();

    private int mFacing;

    private AspectRatio mAspectRatio;

    private boolean mAutoFocus;

    private int mFlash;

    private int mDisplayOrientation;

    private boolean mStartVideoRecording = false;

    private boolean mRecording = false;

    private String mVideoFilePath;

    private int mVideoEncodingBitRate;

    private int mVideoFrameRate;

    private int mMinVideoWidth;

    private int mMinVideoHeight;

    private HandlerThread mBackgroundThread;

    private Handler mBackgroundHandler;

    private boolean mVideoMode = true;

    public Camera2(Callback callback, Context context) {
        super(callback);
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    TextureView.SurfaceTextureListener getSurfaceTextureListener() {
        return mSurfaceTextureListener;
    }


    @Override
    void startVideoMode(String videoFilePath) {
        mVideoFilePath = videoFilePath;
        mVideoMode = true;
        startBackgroundThread();
        chooseCameraIdByFacing();
        collectCameraInfo();
        prepareMediaRecorder();
        startOpeningCamera();
    }

    @Override
    void startPictureMode() {
        mVideoMode = false;
        startBackgroundThread();
        chooseCameraIdByFacing();
        collectCameraInfo();
        prepareImageReader();
        startOpeningCamera();
    }

    @Override
    void stop() {
        closePreviewSession();
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        if (null != mMediaRecorder) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
        mRecording = false;

        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    @Override
    void setVideoEncodingBitRate(int videoEncodingBitRate) {
        mVideoEncodingBitRate = videoEncodingBitRate;
    }

    @Override
    int getVideoEncodingBitRate() {
        return mVideoEncodingBitRate;
    }

    @Override
    void setVideoFrameRate(int videoFrameRate) {
        mVideoFrameRate = videoFrameRate;
    }

    @Override
    int getVideoFrameRate() {
        return mVideoFrameRate;
    }

    @Override
    void setMinVideoWidth(int minVideoWidth) {
        mMinVideoWidth = minVideoWidth;
    }

    @Override
    int getMinVideoWidth() {
        return mMinVideoWidth;
    }

    @Override
    void setMinVideoHeight(int minVideoHeight) {
        mMinVideoHeight = minVideoHeight;
    }

    @Override
    int getMinVideoHeight() {
        return mMinVideoHeight;
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (ratio.equals(mAspectRatio)) {
            // TODO: Better error handling
            return false;
        }

        if (!mPreviewSizes.ratios().isEmpty() && !mPreviewSizes.ratios().contains(ratio)) {
            // If preview sizes were already collected and the new ratio is not in one of them we ignore it
            return false;
        }

        mAspectRatio = ratio;

        // Update image reader and capture session if there was already an active one
        if (mImageReader != null) {
            prepareImageReader();
        }
        if (mMediaRecorder != null) {
            prepareMediaRecorder();
        }
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
            startCaptureSession();
        }
        return true;
    }

    @Override
    @Nullable
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (mPreviewRequestBuilder != null) {
            updateAutoFocus();
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                                        mCaptureCallback, null);
                } catch (CameraAccessException e) {
                    mAutoFocus = !mAutoFocus; // Revert
                }
            }
        }
    }

    @Override
    boolean getAutoFocus() {
        return mAutoFocus;
    }

    @Override
    void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (mPreviewRequestBuilder != null) {
            updateFlash();
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                                                        mCaptureCallback, null);
                } catch (CameraAccessException e) {
                    mFlash = saved; // Revert
                }
            }
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    void takePicture() {
        mCaptureCallback.clearLockFocusAttemptsCount();
        if (mAutoFocus) {
            lockFocus();
        } else {
            captureStillPicture();
        }
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        configureTransform();
    }

    @Override
    void startRecordingVideo() {
        if (!isCameraOpened()) {
            return;
        }
        mStartVideoRecording = true;
        closePreviewSession();
        startCaptureSession();
    }

    @Override
    void stopRecordingVideo() {
        mStartVideoRecording = false;
        try {
            mRecording = false;
            mMediaRecorder.stop();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to stop video recording.", e);
            //noinspection ResultOfMethodCallIgnored
            new File(mVideoFilePath).delete();
        } finally {
            mMediaRecorder.reset();
        }
    }

    @Override
    boolean isRecordingVideo() {
        return mRecording;
    }

    private void closePreviewSession() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
    }

    /**
     * Chooses a camera ID by the specified camera facing ({@link #mFacing}).
     * <p>
     * <p>This rewrites {@link #mCameraId}, {@link #mCameraCharacteristics}, and optionally
     * {@link #mFacing}.</p>
     */
    private void chooseCameraIdByFacing() {
        try {
            int internalFacing = INTERNAL_FACINGS.get(mFacing);
            final String[] ids = mCameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
                Integer internal = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (internal == null) {
                    throw new NullPointerException("Unexpected state: LENS_FACING null");
                }
                if (internal == internalFacing) {
                    mCameraId = id;
                    mCameraCharacteristics = characteristics;
                    return;
                }
            }
            // Not found
            mCameraId = ids[0];
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            Integer internal = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (internal == null) {
                throw new NullPointerException("Unexpected state: LENS_FACING null");
            }
            for (int i = 0, count = INTERNAL_FACINGS.size(); i < count; i++) {
                if (INTERNAL_FACINGS.valueAt(i) == internal) {
                    mFacing = INTERNAL_FACINGS.keyAt(i);
                    return;
                }
            }
            // The operation can reach here when the only camera device is an external one.
            // We treat it as facing back.
            mFacing = Constants.FACING_BACK;
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera devices", e);
        }
    }

    /**
     * Collects some information from {@link #mCameraCharacteristics}.
     * <p>
     * <p>This rewrites {@link #mPreviewSizes}, {@link #mOutputSizes}, and optionally,
     * {@link #mAspectRatio}.</p>
     */
    private void collectCameraInfo() {
        StreamConfigurationMap map = mCameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            throw new IllegalStateException("Failed to get configuration map: " + mCameraId);
        }
        mPreviewSizes.clear();
        for (android.util.Size size : map.getOutputSizes(SurfaceTexture.class)) {
            int width = size.getWidth();
            int height = size.getHeight();
            if (width <= MAX_PREVIEW_WIDTH && height <= MAX_PREVIEW_HEIGHT) {
                mPreviewSizes.add(new Size(width, height));
            }
        }
        mOutputSizes.clear();
        collectOutputSizes(map);
        for (AspectRatio ratio : mPreviewSizes.ratios()) {
            if (!mOutputSizes.ratios().contains(ratio)) {
                mPreviewSizes.remove(ratio);
            }
        }

        // Get the aspect ratio of the largest available output size that the camera sensor supports (rather than using the preview size)
        // (This is necessary on some phones – e.g. Galaxy S5 w/ 4:3 – where the preview size ratio is supposedly supported but still appears stretched)
        if (mAspectRatio == null || mPreviewSizes.sizes(mAspectRatio).isEmpty()) {
            final Size largest = mOutputSizes.largest();
            mAspectRatio = AspectRatio.of(largest.getWidth(), largest.getHeight());
        }
    }

    private void collectOutputSizes(StreamConfigurationMap map) {
        android.util.Size[] sizes = mVideoMode ?
                map.getOutputSizes(MediaRecorder.class) : map.getOutputSizes(ImageFormat.JPEG);
        for (android.util.Size size : sizes) {
            mOutputSizes.add(new Size(size.getWidth(), size.getHeight()));
        }
    }

    private void prepareImageReader() {
        if (mImageReader != null) {
            mImageReader.close();
        }
        Size largest = mOutputSizes.sizes(mAspectRatio).last();
        mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                                               ImageFormat.JPEG, /* maxImages */ 2);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
    }

    private void prepareMediaRecorder() {
        try {
            if (mMediaRecorder == null) {
                mMediaRecorder = new MediaRecorder();
            } else {
                mMediaRecorder.reset();
            }

            Size videoSize = chooseVideoSize(mMinVideoWidth, mMinVideoHeight);

            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setOutputFile(mVideoFilePath);
            mMediaRecorder.setVideoEncodingBitRate(mVideoEncodingBitRate);
            mMediaRecorder.setVideoFrameRate(mVideoFrameRate);
            mMediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mMediaRecorder.setOrientationHint(getCameraOrientation());
            mMediaRecorder.prepare();
        } catch (IOException e) {
            throw new RuntimeException("Unable to prepare MediaRecorder ", e);
        }
    }

    /**
     * Starts opening a camera device.
     * <p>
     * <p>The result will be processed in {@link #mCameraDeviceCallback}.</p>
     */
    private void startOpeningCamera() {
        try {
            mCameraManager.openCamera(mCameraId, mCameraDeviceCallback, null);
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to open camera: " + mCameraId, e);
        }
    }

    /**
     * Starts a capture session for camera preview.
     * <p>
     * <p>This rewrites {@link #mPreviewRequestBuilder}.</p>
     * <p>
     * <p>The result will be continuously processed in {@link #mSessionCallback}.</p>
     */
    private void startCaptureSession() {
        if (!isCameraOpened() || mSurfaceInfo.surface == null
                || (mVideoMode && mMediaRecorder == null) || (!mVideoMode && mImageReader == null)) {
            return;
        }
        Size previewSize = chooseOptimalSize();
        mSurfaceInfo.surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface surface = new Surface(mSurfaceInfo.surface);
        try {
            if (mStartVideoRecording) {
                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                mPreviewRequestBuilder.addTarget(surface);
                mPreviewRequestBuilder.addTarget(mMediaRecorder.getSurface());
                mCamera.createCaptureSession(Arrays.asList(surface, mMediaRecorder.getSurface()),
                                             mSessionCallback, mBackgroundHandler);
            } else {
                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(surface);
                Surface outputSurface = !mVideoMode ? mImageReader.getSurface() : mMediaRecorder.getSurface();
                mCamera.createCaptureSession(Arrays.asList(surface, outputSurface),
                                             mSessionCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to start camera session");
        }
    }

    private Size chooseVideoSize(int width, int height) {
        SortedSet<Size> bestSizes = mOutputSizes.sizes(mAspectRatio);
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : bestSizes) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new Comparator<Size>() {
                @Override
                public int compare(Size size1, Size size2) {
                    return Long.compare(size1.getArea(), size2.getArea());
                }
            });
        } else {
            Log.e(TAG, "Couldn't find any suitable video recording size");
            return bestSizes.last();
        }
    }

    /**
     * Chooses the optimal preview size based on {@link #mPreviewSizes} and {@link #mSurfaceInfo}.
     *
     * @return The picked size for camera preview.
     */
    private Size chooseOptimalSize() {
        int surfaceLonger, surfaceShorter;
        if (mSurfaceInfo.width < mSurfaceInfo.height) {
            surfaceLonger = mSurfaceInfo.height;
            surfaceShorter = mSurfaceInfo.width;
        } else {
            surfaceLonger = mSurfaceInfo.width;
            surfaceShorter = mSurfaceInfo.height;
        }
        SortedSet<Size> candidates = mPreviewSizes.sizes(mAspectRatio);
        // Pick the smallest of those big enough.
        for (Size size : candidates) {
            if (size.getWidth() >= surfaceLonger && size.getHeight() >= surfaceShorter) {
                return size;
            }
        }
        // If no size is big enough, pick the largest one.
        return candidates.last();
    }

    /**
     * Configures the transform matrix for TextureView based on {@link #mDisplayOrientation} and
     * {@link #mSurfaceInfo}.
     */
    private void configureTransform() {
        Matrix matrix = new Matrix();
        if (mDisplayOrientation % 180 == 90) {
            // Rotate the camera preview when the screen is landscape.
            matrix.setPolyToPoly(
                    new float[]{
                            0.f, 0.f, // top left
                            mSurfaceInfo.width, 0.f, // top right
                            0.f, mSurfaceInfo.height, // bottom left
                            mSurfaceInfo.width, mSurfaceInfo.height, // bottom right
                    }, 0,
                    mDisplayOrientation == 90 ?
                            // Clockwise
                            new float[]{
                                    0.f, mSurfaceInfo.height, // top left
                                    0.f, 0.f, // top right
                                    mSurfaceInfo.width, mSurfaceInfo.height, // bottom left
                                    mSurfaceInfo.width, 0.f, // bottom right
                            }
                            : // mDisplayOrientation == 270
                            // Counter-clockwise
                            new float[]{
                                    mSurfaceInfo.width, 0.f, // top left
                                    mSurfaceInfo.width, mSurfaceInfo.height, // top right
                                    0.f, 0.f, // bottom left
                                    0.f, mSurfaceInfo.height, // bottom right
                            }, 0,
                    4);
        }
        mCallback.onTransformUpdated(matrix);
    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
    private void updateAutoFocus() {
        int afMode = CaptureRequest.CONTROL_AF_MODE_OFF;
        if (mAutoFocus) {
            afMode = FocusModeSelector.getBestAfMode(mCameraCharacteristics);
        }
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
    private void updateFlash() {
        switch (mFlash) {
            case Constants.FLASH_OFF:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                           CaptureRequest.CONTROL_AE_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                           CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_ON:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                           CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                           CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_TORCH:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                           CaptureRequest.CONTROL_AE_MODE_ON);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                           CaptureRequest.FLASH_MODE_TORCH);
                break;
            case Constants.FLASH_AUTO:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                           CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                           CaptureRequest.FLASH_MODE_OFF);
                break;
            case Constants.FLASH_RED_EYE:
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                           CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                           CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    /**
     * Locks the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                       CaptureRequest.CONTROL_AF_TRIGGER_START);
            mCaptureCallback.setState(PictureCaptureCallback.STATE_LOCKING);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to lock focus.", e);
        }
    }

    /**
     * Captures a still picture.
     */
    private void captureStillPicture() {
        try {
            CaptureRequest.Builder captureRequestBuilder = mCamera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                      mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));
            switch (mFlash) {
                case Constants.FLASH_OFF:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                              CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                              CaptureRequest.FLASH_MODE_OFF);
                    break;
                case Constants.FLASH_ON:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                              CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    break;
                case Constants.FLASH_TORCH:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                              CaptureRequest.CONTROL_AE_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                              CaptureRequest.FLASH_MODE_TORCH);
                    break;
                case Constants.FLASH_AUTO:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                              CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
                case Constants.FLASH_RED_EYE:
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                              CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
            }
            // Calculate JPEG orientation.
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, getCameraOrientation());
            // Stop preview and capture a still picture.
            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureRequestBuilder.build(),
                                    new CameraCaptureSession.CaptureCallback() {
                                        @Override
                                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                       @NonNull CaptureRequest request,
                                                                       @NonNull TotalCaptureResult result) {
                                            unlockFocus();
                                        }
                                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot capture a still picture.", e);
        }
    }

    private int getCameraOrientation() {
        @SuppressWarnings("ConstantConditions")
        int sensorOrientation = mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION);
        return (sensorOrientation + mDisplayOrientation * (mFacing == Constants.FACING_FRONT ? 1 : -1) + 360) % 360;
    }

    /**
     * Unlocks the auto-focus and restart camera preview. This is supposed to be called after
     * capturing a still picture.
     */
    private void unlockFocus() {
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                   CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, null);
            updateAutoFocus();
            updateFlash();
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                       CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                                                null);
            mCaptureCallback.setState(PictureCaptureCallback.STATE_PREVIEW);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to restart camera preview.", e);
        }
    }

}
