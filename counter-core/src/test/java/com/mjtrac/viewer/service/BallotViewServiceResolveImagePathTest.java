/*
 * Copyright (C) 2026 Mitch Trachtenberg — GPL v3
 */
package com.mjtrac.viewer.service;

import com.mjtrac.counter.entity.BallotImage;
import com.mjtrac.viewer.service.BallotViewService.BallotView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real, longstanding bug (predates this project's
 * desktop-app work — traced via git history back to the original bSuite
 * v1.0 commit): BallotView's ".counted" fallback replaced the file's
 * extension ("foo.png" -> "foo.counted"), but VoteRecordService actually
 * renames counted images by appending ".counted" to the whole original
 * filename ("foo.png" -> "foo.png.counted"). The two never matched, so any
 * ballot that had actually been counted failed to resolve in the Viewer —
 * found via a real user report of "Could not load image" in viewer.
 */
class BallotViewServiceResolveImagePathTest {

    @TempDir
    Path tempDir;

    private static BallotImage imageAt(String path) throws ReflectiveOperationException {
        BallotImage image = new BallotImage();
        image.setImagePath(path);
        image.setImageName("test");
        // id has no public setter (JPA-generated) — BallotView's constructor
        // unboxes it, so it can't be left null for this DB-free test.
        java.lang.reflect.Field idField = BallotImage.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(image, 1L);
        return image;
    }

    @Test
    void resolvesToOriginalPathWhenFileStillExists() throws Exception {
        Path png = tempDir.resolve("ballot.png");
        Files.writeString(png, "fake image bytes");

        BallotView view = new BallotView(imageAt(png.toString()), List.of());

        assertThat(view.getResolvedPath()).isEqualTo(png.toString());
    }

    @Test
    void resolvesToCountedSuffixWhenOriginalIsGone() throws Exception {
        Path original = tempDir.resolve("ballot.png");
        Path counted = tempDir.resolve("ballot.png.counted");
        Files.writeString(counted, "fake image bytes");
        // original was renamed away by VoteRecordService — it never exists
        // alongside the .counted file.

        BallotView view = new BallotView(imageAt(original.toString()), List.of());

        assertThat(view.getResolvedPath()).isEqualTo(counted.toString());
    }

    @Test
    void fallsBackToOriginalPathWhenNeitherExists() throws Exception {
        Path missing = tempDir.resolve("nowhere.png");

        BallotView view = new BallotView(imageAt(missing.toString()), List.of());

        assertThat(view.getResolvedPath()).isEqualTo(missing.toString());
    }
}
