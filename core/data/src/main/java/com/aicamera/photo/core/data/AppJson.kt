package com.aicamera.photo.core.data

import kotlinx.serialization.json.Json

/** Shared JSON configuration for persisted structured values. Tolerant of added fields across versions. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}
