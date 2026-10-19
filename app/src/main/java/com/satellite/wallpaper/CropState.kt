package com.satellite.wallpaper

data class CropState(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    fun clamped(): CropState = copy(
        zoom = zoom.coerceIn(1f, 4f),
        offsetX = offsetX.coerceIn(-1f, 1f),
        offsetY = offsetY.coerceIn(-1f, 1f)
    )
}
