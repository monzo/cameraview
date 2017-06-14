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

import android.graphics.Matrix;
import android.view.TextureView;

import java.util.Set;

abstract class CameraViewImpl {

    protected final Callback mCallback;

    public CameraViewImpl(Callback callback) {
        mCallback = callback;
    }

    abstract TextureView.SurfaceTextureListener getSurfaceTextureListener();

    abstract void start();

    abstract void stop();

    abstract boolean isCameraOpened();

    abstract void setFacing(int facing);

    abstract int getFacing();

    abstract void setVideoEncodingBitRate(int videoEncodingBitRate);

    abstract int getVideoEncodingBitRate();

    abstract void setVideoFrameRate(int videoFrameRate);

    abstract int getVideoFrameRate();

    abstract void setMinVideoWidth(int minVideoWidth);

    abstract int getMinVideoWidth();

    abstract void setMinVideoHeight(int minVideoHeight);

    abstract int getMinVideoHeight();

    abstract Set<AspectRatio> getSupportedAspectRatios();

    /**
     * @return {@code true} if the aspect ratio was changed.
     */
    abstract boolean setAspectRatio(AspectRatio ratio);

    abstract AspectRatio getAspectRatio();

    abstract void setAutoFocus(boolean autoFocus);

    abstract boolean getAutoFocus();

    abstract void setFlash(int flash);

    abstract int getFlash();

    abstract void takePicture();

    abstract void setDisplayOrientation(int displayOrientation);

    abstract void startRecordingVideo(String videoFilePath);

    abstract void stopRecordingVideo();

    abstract boolean isRecordingVideo();

    interface Callback {

        void onCameraOpened();

        void onCameraClosed();

        void onPictureTaken(byte[] data);

        void onTransformUpdated(Matrix matrix);

    }

}
