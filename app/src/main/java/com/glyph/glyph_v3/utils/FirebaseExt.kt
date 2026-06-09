package com.glyph.glyph_v3.utils

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Converts a Firebase Task into a suspend function, allowing it to be used in coroutines.
suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        val e = exception
        return if (e == null) {
            if (isCanceled) throw RuntimeException("Task was cancelled.")
            @Suppress("UNCHECKED_CAST")
            result as T
        } else {
            throw e
        }
    }

    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { exception ->
            continuation.resumeWithException(exception)
        }
        addOnCanceledListener {
            continuation.resumeWithException(RuntimeException("Task was cancelled."))
        }
    }
}
