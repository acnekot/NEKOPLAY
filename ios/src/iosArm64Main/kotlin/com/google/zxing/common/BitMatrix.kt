package com.google.zxing.common

class BitMatrix(val width: Int, val height: Int) {
    private val bits = BooleanArray(width * height)
    operator fun get(x: Int, y: Int) = bits[y * width + x]
    fun set(x: Int, y: Int) { bits[y * width + x] = true }
}
