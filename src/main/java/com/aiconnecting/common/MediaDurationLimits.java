package com.aiconnecting.common;

/** Shared duration limits for media request validation and settlement. */
public final class MediaDurationLimits {

    /** Maximum supported video duration: 24 hours, in seconds. */
    public static final int MAX_VIDEO_DURATION_SECONDS = 24 * 60 * 60;

    private MediaDurationLimits() {
    }
}
