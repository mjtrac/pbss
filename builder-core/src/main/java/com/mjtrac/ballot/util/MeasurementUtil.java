/*
 * Copyright (C) 2026 Mitch Trachtenberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.mjtrac.ballot.util;

/**
 * Conversion utilities between PDF points, inches, and millimetres.
 *
 * PDF coordinate system: 72 points = 1 inch.
 */
public final class MeasurementUtil {
    private MeasurementUtil() {}

    public static final float PT_PER_INCH = 72f;
    public static final float MM_PER_INCH = 25.4f;

    public static double ptToInches(float pt)    { return (double) pt / PT_PER_INCH; }
    public static double ptToMm(float pt)        { return ptToInches(pt) * MM_PER_INCH; }
    public static double inchesToMm(double inch) { return inch * 25.4; }  // use double literal to avoid float precision loss
    public static float  inchesToPt(double inch) { return (float)(inch * PT_PER_INCH); }
    public static float  mmToPt(double mm)       { return inchesToPt(mm / MM_PER_INCH); }
}
