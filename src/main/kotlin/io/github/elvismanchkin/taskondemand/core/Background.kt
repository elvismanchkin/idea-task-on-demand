package io.github.elvismanchkin.taskondemand.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Runs [work] on a background thread and then calls [onSuccess] or [onError] on the EDT.
 * With a [title] the work shows in the status-bar progress. Without one it runs silently, e.g. on branch changes.
 */
fun <T> runInBackground(
    project: Project?,
    title: String?,
    work: () -> T,
    onError: (Throwable) -> Unit = {},
    onSuccess: (T) -> Unit,
) {
    if (title != null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                // Cancellation must propagate; the platform then skips the result and no error is shown.
                result = runCatching(work).onFailure { if (it is ProcessCanceledException) throw it }
            }

            override fun onFinished() {
                if (project?.isDisposed == true) return
                result?.fold(onSuccess, onError)
            }
        })
        return
    }
    // Captured on the caller's thread: inside a dialog the callbacks still run, but never during an unrelated modal one.
    val modality = ModalityState.defaultModalityState()
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        val result = runCatching(work)
        if (result.exceptionOrNull() is ProcessCanceledException) return@executeOnPooledThread
        app.invokeLater({ result.fold(onSuccess, onError) }, modality) { project?.isDisposed == true }
    }
}

/** A user-facing message for an exception raised by the REST clients. */
fun Throwable.userMessage(): String = when (this) {
    is RemoteException -> message ?: "Request failed"
    else -> "${javaClass.simpleName}: ${message ?: "unexpected error"}"
}
