package io.github.elvismanchkin.taskondemand.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
                result = runCatching(work)
            }

            override fun onFinished() {
                if (project?.isDisposed == true) return
                result?.fold(onSuccess, onError)
            }
        })
        return
    }
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        val result = runCatching(work)
        app.invokeLater({ result.fold(onSuccess, onError) }, ModalityState.any()) { project?.isDisposed == true }
    }
}

/** A user-facing message for an exception raised by the REST clients. */
fun Throwable.userMessage(): String = when (this) {
    is RemoteException -> message ?: "Request failed"
    else -> "${javaClass.simpleName}: ${message ?: "unexpected error"}"
}
