package com.google.android.cameraview;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.support.annotation.NonNull;

/**
 * A {@link CameraCaptureSession.CaptureCallback} for capturing a still picture.
 */
abstract class PictureCaptureCallback extends CameraCaptureSession.CaptureCallback {
    private static final int MAX_LOCK_FOCUS_ATTEMPTS = 3;

    public static final int STATE_PREVIEW = 0;
    public static final int STATE_LOCKING = 1;
    public static final int STATE_LOCKED = 2;
    public static final int STATE_PRECAPTURE = 3;
    public static final int STATE_WAITING = 4;
    public static final int STATE_CAPTURING = 5;

    private int mState;
    private int mLockFocusAttemptsCount;


    public void setState(int state) {
        mState = state;
    }

    public void clearLockFocusAttemptsCount() {
        mLockFocusAttemptsCount = 0;
    }

    @Override
    public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureResult partialResult) {
        process(partialResult);
    }

    @Override
    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                   @NonNull CaptureRequest request,
                                   @NonNull TotalCaptureResult result) {
        process(result);
    }

    private void process(@NonNull CaptureResult result) {
        switch (mState) {
            case STATE_LOCKING: {
                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                if (af == null) {
                    break;
                }
                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                boolean focused = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED;
                boolean notFocused = af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                if (focused || (notFocused && mLockFocusAttemptsCount >= MAX_LOCK_FOCUS_ATTEMPTS)) {
                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        setState(STATE_CAPTURING);
                        onReady();
                    } else {
                        setState(STATE_LOCKED);
                        onPrecaptureRequired();
                    }
                } else if (notFocused) {
                    onLockFocusRetryRequired();
                    mLockFocusAttemptsCount++;
                }
                break;
            }
            case STATE_PRECAPTURE: {
                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        ae == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    setState(STATE_WAITING);
                }
                break;
            }
            case STATE_WAITING: {
                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    setState(STATE_CAPTURING);
                    onReady();
                }
                break;
            }
        }
    }

    /**
     * Called when it is ready to take a still picture.
     */
    public abstract void onReady();

    /**
     * Called when it is necessary to run the precapture sequence.
     */
    public abstract void onPrecaptureRequired();
    /**
     * Called when locking focus has failed but MAX_LOCK_FOCUS_ATTEMPTS hasn't been reached yet
     */
    public abstract void onLockFocusRetryRequired();
}
