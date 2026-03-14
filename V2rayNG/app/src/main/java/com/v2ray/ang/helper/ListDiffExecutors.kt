package com.v2ray.ang.helper

import java.util.concurrent.Executor
import java.util.concurrent.Executors

object ListDiffExecutors {
    private val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    val background: Executor = Executors.newFixedThreadPool(threadCount)
}
