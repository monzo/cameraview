package com.google.android.cameraview;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

final class FocusModeSelector {
    // Ordered by descending preference
    private static int[] mPreferredAfModes = {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_AUTO
    };

    private FocusModeSelector() {
    }

    static int getBestAfMode(CameraCharacteristics cameraCharacteristics) {
        int[] supportedModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        @SuppressWarnings("ConstantConditions")
        int hardwareLevel = cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        boolean isLegacyHardware = hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        for (int mode : mPreferredAfModes) {
            // Skip continuous AF, since some devices with legacy hardware will not work with continuous picture.
            // e.g. Samsung S5, Motorola Moto G
            if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE && isLegacyHardware) {
                continue;
            }
            if (arrayOfIntContains(supportedModes, mode)) {
                return mode;
            }
        }
        return CaptureRequest.CONTROL_AF_MODE_OFF;
    }

    private static boolean arrayOfIntContains(int[] array, int value) {
        if (array == null) return false;
        for (int arrayValue : array) {
            if (arrayValue == value) {
                return true;
            }
        }
        return false;
    }
}
