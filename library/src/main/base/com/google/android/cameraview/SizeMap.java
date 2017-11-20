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

import android.content.res.Configuration;
import android.support.annotation.Nullable;
import android.support.v4.util.ArrayMap;
import android.support.v4.util.ArraySet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A collection class that automatically groups {@link Size}s by their {@link AspectRatio}s.
 */
class SizeMap {

    private final ArrayMap<AspectRatio, SortedSet<Size>> mRatios = new ArrayMap<>();

    /**
     * Add a new {@link Size} to this collection.
     *
     * @param size The size to add.
     * @return {@code true} if it is added, {@code false} if it already exists and is not added.
     */
    public boolean add(Size size) {
        for (AspectRatio ratio : mRatios.keySet()) {
            if (ratio.matches(size)) {
                final SortedSet<Size> sizes = mRatios.get(ratio);
                if (sizes.contains(size)) {
                    return false;
                } else {
                    sizes.add(size);
                    return true;
                }
            }
        }
        // None of the existing ratio matches the provided size; add a new key
        SortedSet<Size> sizes = new TreeSet<>();
        sizes.add(size);
        mRatios.put(AspectRatio.of(size.getWidth(), size.getHeight()), sizes);
        return true;
    }

    /**
     * Removes the specified aspect ratio and all sizes associated with it.
     *
     * @param ratio The aspect ratio to be removed.
     */
    public void remove(AspectRatio ratio) {
        mRatios.remove(ratio);
    }

    Set<AspectRatio> ratios() {
        return mRatios.keySet();
    }

    List<AspectRatio> ratiosSortedByClosest(final AspectRatio aspectRatio) {
        List<AspectRatio> aspectRatios = new ArrayList<>(ratios());
        Collections.sort(aspectRatios, new Comparator<AspectRatio>() {
            @Override public int compare(AspectRatio ratio1, AspectRatio ratio2) {
                float aspectRatioValue = aspectRatio.toFloat();
                float ratio1Value = ratio1.toFloat();
                float ratio2Value = ratio2.toFloat();

                float ratio1Diff = Math.abs(ratio1Value - aspectRatioValue);
                float ratio2Diff = Math.abs(ratio2Value - aspectRatioValue);
                return Float.compare(ratio1Diff, ratio2Diff);
            }
        });
        return aspectRatios;
    }

    SortedSet<Size> sizes(AspectRatio ratio) {
        if (mRatios.containsKey(ratio)) {
            return mRatios.get(ratio);
        }
        return new TreeSet<>();
    }

    Size largest() {
        Set<Size> preferredSizes = new ArraySet<>();
        for (AspectRatio ratio : mRatios.keySet()) {
                preferredSizes.addAll(mRatios.get(ratio));
        }
        return Collections.max(preferredSizes, new CompareSizesByArea());
    }

    void clear() {
        mRatios.clear();
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum(lhs.getArea() - rhs.getArea());
        }
    }
}
