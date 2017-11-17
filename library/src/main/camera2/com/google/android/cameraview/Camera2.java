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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import timber.log.Timber;


@SuppressWarnings("MissingPermission")
@TargetApi(Build.VERSION_CODES.LOLLIPOP) class Camera2 extends CameraViewImpl {
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
            Timber.d("Surface texture available, size %dx%d ", width, height);
            mSurfaceInfo.configure(surface, width, height);
            startCaptureSession();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            Timber.d("Surface texture size changed, new size %dx%d ", width, height);
            mSurfaceInfo.configure(surface, width, height);
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
            Timber.e("onError: cameraId: %s error: %d", camera.getId(), error);
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
                        Timber.e(e, "Failed to start camera preview.");
                    }
                }
            });
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Timber.e("Failed to configure capture session.");
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
                Timber.e(e, "Failed to run precapture sequence.");
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

    @Nullable private Size mSelectPreviewSize = null;

    private final SizeMap mOutputSizes = new SizeMap();

    private int mFacing;

    private AspectRatio[] mPreferredRatios = new AspectRatio[0];

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

    Camera2(Callback callback, Context context) {
        super(callback);
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    TextureView.SurfaceTextureListener getSurfaceTextureListener() {
        return mSurfaceTextureListener;
    }


    @Override
    void startVideoMode() {
        mVideoMode = true;
        startBackgroundThread();
        chooseCameraIdByFacing();
        collectCameraInfo();
        startOpeningCamera();
    }

    @Override
    void startPictureMode() {
        mVideoMode = false;
        startBackgroundThread();
        chooseCameraIdByFacing();
        collectCameraInfo();
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
            if (mVideoMode) {
                startVideoMode();
            } else {
                startPictureMode();
            }
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
        if (mSelectPreviewSize != null) {
            configureTransform(mSelectPreviewSize);
        }
    }

    @Override
    void startRecordingVideo(String videoFilePath) {
        try {
            if (!isCameraOpened()) {
                return;
            }
            mVideoFilePath = videoFilePath;
            mStartVideoRecording = true;
            prepareMediaRecorder();
            closePreviewSession();
            startCaptureSession();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    void stopRecordingVideo() {
        mStartVideoRecording = false;
        try {
            mRecording = false;
            mMediaRecorder.stop();
        } catch (RuntimeException e) {
            Timber.e(e, "Failed to stop video recording.");
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
     * <p>This rewrites {@link #mPreviewSizes}, {@link #mOutputSizes}
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
    }

    private void collectOutputSizes(StreamConfigurationMap map) {
        android.util.Size[] sizes = mVideoMode ?
                map.getOutputSizes(MediaRecorder.class) : map.getOutputSizes(ImageFormat.JPEG);
        for (android.util.Size size : sizes) {
            mOutputSizes.add(new Size(size.getWidth(), size.getHeight()));
        }
    }

    private void prepareImageReader(AspectRatio aspectRatio) {
        if (mImageReader != null) {
            mImageReader.close();
        }
        Size largest = mOutputSizes.sizes(aspectRatio).last();
        Timber.d("Image output size selected: %s, ratio: %s", largest, largest.getAspectRatio());
        mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, null);
    }

    private void prepareMediaRecorder() throws IOException {
        if (mMediaRecorder == null) {
            mMediaRecorder = new MediaRecorder();
        } else {
            mMediaRecorder.reset();
        }

        Size minVideoSize = new Size(mMinVideoWidth, mMinVideoHeight);
        Size videoSize = chooseVideoSize(minVideoSize, mSelectPreviewSize);
        Timber.d("Video output size selected: %s, ratio: %s ", videoSize, videoSize.getAspectRatio());

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
        if (!isCameraOpened() || mSurfaceInfo.surface == null) {
            return;
        }
        try {
            mSelectPreviewSize = chooseOptimalSize();
            configureTransform(mSelectPreviewSize);

            mSurfaceInfo.surface.setDefaultBufferSize(mSelectPreviewSize.getWidth(), mSelectPreviewSize.getHeight());
            Surface surface = new Surface(mSurfaceInfo.surface);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(surface);

            if (mStartVideoRecording) {
                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                mPreviewRequestBuilder.addTarget(mMediaRecorder.getSurface());
                mPreviewRequestBuilder.addTarget(surface);
                outputs.add(mMediaRecorder.getSurface());
            } else {
                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewRequestBuilder.addTarget(surface);
                if (!mVideoMode) {
                    AspectRatio previewRatio = AspectRatio.of(mSelectPreviewSize.getWidth(), mSelectPreviewSize.getHeight());
                    prepareImageReader(previewRatio);
                    outputs.add(mImageReader.getSurface());
                }
            }
            mCamera.createCaptureSession(outputs, mSessionCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException("Failed to start capture session for mode " + (mVideoMode ? "video" : "picture"), e);
        }
    }

    private Size chooseVideoSize(Size minVideoSize, Size currentPreviewSize) {
        SortedSet<Size> bestSizes = mOutputSizes.sizes(currentPreviewSize.getAspectRatio());
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : bestSizes) {
            if (option.getWidth() >= minVideoSize.getWidth() && option.getHeight() >= minVideoSize.getHeight()) {
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
            Size fallback = bestSizes.last();
            Timber.e("Couldn't find any suitable video recording size - falling back to %s with ratio %s",
                     fallback, fallback.getAspectRatio());
            return fallback;
        }
    }

    /**
     * Chooses the optimal preview size based on {@link #mPreviewSizes} and {@link #mSurfaceInfo}.
     *
     * @return The picked size for camera preview.
     */
    private Size chooseOptimalSize() {
        int surfaceWidth = mSurfaceInfo.width;
        int surfaceHeight = mSurfaceInfo.height;
        int cameraOrientation = getCameraOrientation();
        // Flip with height around depending on camera orientation
        if (cameraOrientation == 90 || cameraOrientation == 270) {
            Timber.d("Flipping surface width and height because camera orientation is 90 or 270");
            surfaceWidth = mSurfaceInfo.height;
            surfaceHeight = mSurfaceInfo.width;
        }

        AspectRatio surfaceRatio = AspectRatio.of(surfaceWidth, surfaceHeight);

        List<Size> bigEnough = new ArrayList<>();
        List<AspectRatio> ratiosSorted = mPreviewSizes.ratiosSortedByClosest(surfaceRatio);

        for (AspectRatio ratio : ratiosSorted) {
            Timber.d("Choosing optimal size, trying with ratio %s, surface ratio is %s", ratio, surfaceRatio);
            for (Size size : mPreviewSizes.sizes(ratio)) {
                if (size.getWidth() >= surfaceWidth && size.getHeight() >= surfaceHeight) {
                    bigEnough.add(size);
                }
            }

            if (!bigEnough.isEmpty()) {
                Size size = Collections.min(bigEnough);
                Timber.d("Selected preview size %s with ratio %s", size, ratio);
                return size;
            }

            Timber.d("Not preview size available for ratio " + ratio);
        }

        Size largest = mPreviewSizes.largest();
        Timber.e("Not big enough preview size found for surface, selecting largest available %s", largest);
        return largest;
    }


    /**
     * Configures the transform matrix for TextureView based on {@link #mDisplayOrientation} and
     * {@link #mSurfaceInfo}.
     */
    private void configureTransform(Size previewSize) {
        Matrix matrix = new Matrix();
        boolean isLandscape = mDisplayOrientation % 180 == 90;
        if (isLandscape) {
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

        // If the aspect ratio of the TextureView surface doesn't match the ratio, we need to crop the surface view
        // so that the preview doesn't look stretched or squashed.
        int previewWidth = previewSize.getWidth();
        int previewHeight = previewSize.getHeight();
        int cameraOrientation = getCameraOrientation();
        if (cameraOrientation == 270 || cameraOrientation == 90) {
            previewWidth = previewSize.getHeight();
            previewHeight = previewSize.getWidth();
        }

        float ratioSurface = (float) mSurfaceInfo.width / mSurfaceInfo.height;
        float ratioPreview = (float) previewWidth / previewHeight;

        float scaleX;
        float scaleY;

        if (ratioPreview < ratioSurface) {
            scaleX = 1;
            scaleY = (float) previewHeight / mSurfaceInfo.height;
        } else {
            scaleX = (float) previewWidth / mSurfaceInfo.width;
            scaleY = 1;
        }
        matrix.setScale(scaleX, scaleY);

        // If we scaled we also have to translate so that the preview is centered
        float translateX = scaleX != 1 ? (((mSurfaceInfo.width * scaleX) - mSurfaceInfo.width) / 2) * -1 : 0;
        float translateY = scaleY != 1 ? (((mSurfaceInfo.height * scaleY) - mSurfaceInfo.height) / 2) * -1 : 0;
        matrix.postTranslate(translateX, translateY);

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
            Timber.e(e, "Failed to lock focus.");
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
            Timber.e(e, "Cannot capture a still picture.");
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
            Timber.e(e, "Failed to restart camera preview.");
        }
    }

}
