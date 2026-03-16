package com.xray.ang.extension

fun <T> MutableList<T>.swapSafely(fromIndex: Int, toIndex: Int): Boolean {
    if (fromIndex !in indices || toIndex !in indices) {
        return false
    }
    if (fromIndex == toIndex) {
        return true
    }
    val item = this[fromIndex]
    this[fromIndex] = this[toIndex]
    this[toIndex] = item
    return true
}
