// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyTest {
    @Test fun animationsAutoplayOnlyWithAnimationsOnAndBatterySaverOff() {
        assertTrue(MediaMotion.autoplay(1f, powerSave = false))
        assertFalse(MediaMotion.autoplay(0f, powerSave = false))
        assertFalse(MediaMotion.autoplay(1f, powerSave = true))
    }

    @Test fun atMostTwoAnimationsHoldASlotAndReleasedSlotsAreReused() {
        val slots = AnimationSlots()
        assertTrue(slots.holds("a", wanted = true))
        assertTrue(slots.holds("b", wanted = true))
        assertFalse(slots.holds("c", wanted = true))
        assertTrue(slots.holds("a", wanted = true))
        assertFalse(slots.holds("a", wanted = false))
        assertTrue(slots.holds("c", wanted = true))
        slots.release("b")
        assertTrue(slots.holds("d", wanted = true))
    }

    @Test fun videosScaleTheLongEdgeTo1280WithoutUpscalingAndFollowRotation() {
        assertNull(MediaVideo.targetSize(VideoProbe(1280, 720, 0, 1_000)))
        assertEquals(1280 to 720, MediaVideo.targetSize(VideoProbe(1920, 1080, 0, 1_000)))
        // A portrait phone video is stored landscape with a rotation; the output is portrait.
        assertEquals(720 to 1280, MediaVideo.targetSize(VideoProbe(1920, 1080, 90, 1_000)))
        assertEquals(1280 to 960, MediaVideo.targetSize(VideoProbe(4000, 3000, 0, 1_000)))
    }

    @Test fun videoCapsMatchTheOwnerDecision() {
        assertEquals(1_280, MediaVideo.MAX_EDGE)
        assertEquals(600_000L, MediaVideo.MAX_DURATION_MS)
        assertEquals(200L * 1024 * 1024, MediaVideo.MAX_BYTES)
        assertEquals(15L * 1024 * 1024, MediaNormalizer.MAX_ANIMATED_BYTES)
    }
}
