/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-2019 Datadog, Inc.
 */

package com.datadog.android.log.internal.file

import android.content.Context
import com.datadog.android.log.internal.LogReader
import com.datadog.android.log.internal.LogStrategy
import com.datadog.android.log.internal.LogWriter
import java.io.File

internal class LogFileStrategy(
    private val rootDir: File,
    private val recentDelayMs: Long
) : LogStrategy {

    constructor(
        context: Context,
        recentDelayMs: Long = MAX_DELAY_BETWEEN_LOGS_MS
    ) :
        this(File(context.filesDir, LOGS_FOLDER_NAME), recentDelayMs)

    // region LogPersistingStrategy

    override fun getLogWriter(): LogWriter {
        return LogFileWriter(rootDir, recentDelayMs)
    }

    override fun getLogReader(): LogReader {
        return LogFileReader(rootDir, recentDelayMs)
    }

    // endregion

    companion object {

        internal fun isFileRecent(file: File, recentDelayMs: Long): Boolean {
            val now = System.currentTimeMillis()
            val fileTimestamp = file.name.toLong()
            return fileTimestamp >= (now - recentDelayMs)
        }

        internal const val LOGS_FOLDER_NAME = "dd-logs"
        internal const val SEPARATOR_BYTE: Byte = '\n'.toByte()
        internal const val MAX_DELAY_BETWEEN_LOGS_MS = 5000L
    }
}