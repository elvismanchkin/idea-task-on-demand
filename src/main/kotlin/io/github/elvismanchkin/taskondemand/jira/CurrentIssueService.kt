package io.github.elvismanchkin.taskondemand.jira

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.util.messages.Topic
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import java.util.concurrent.atomic.AtomicLong

/** The Jira issue for the current git branch, kept up to date as branches change. */
@Service(Service.Level.PROJECT)
class CurrentIssueService(private val project: Project) : Disposable {

    data class Snapshot(
        val branch: String? = null,
        val issueKey: String? = null,
        val issue: JiraIssue? = null,
        val error: String? = null,
        val loading: Boolean = false,
    )

    fun interface Listener {
        fun currentIssueChanged(snapshot: Snapshot)
    }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(BranchChangeListener.VCS_BRANCH_CHANGED, object : BranchChangeListener {
            override fun branchWillChange(branchName: String) = Unit
            override fun branchHasChanged(branchName: String) = refresh()
        })
        // Also catches checkouts made outside the IDE; refresh() is a no-op when the branch is unchanged.
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { refresh() })
    }

    fun currentBranch(): String? {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val root = project.basePath
        val repository = repositories.firstOrNull { it.root.path == root } ?: repositories.firstOrNull()
        return repository?.currentBranchName
    }

    /** Bumped for every fetch, so a slow answer for an older branch never overwrites a newer one. */
    private val generation = AtomicLong()

    /** Re-reads the branch and fetches the issue if the key changed, or always when [force] is set. */
    fun refresh(force: Boolean = false) {
        if (project.isDisposed) return
        val settings = ProjectSettings.getInstance(project)
        val branch = currentBranch()
        val key = IssueKeys.fromBranch(branch, settings.projectKeys, settings.state.branchIssuePattern)
        val request: Long
        val keepIssue: JiraIssue?
        // Git events arrive on several background threads and on the EDT; compare-and-set must be atomic.
        synchronized(this) {
            val previous = snapshot
            if (!force && previous.branch == branch && previous.issueKey == key) return
            request = generation.incrementAndGet()
            if (key == null || !AppSettings.getInstance().isConfigured(ServerKind.JIRA)) {
                update(Snapshot(branch, key))
                return
            }
            keepIssue = previous.issue?.takeIf { it.key == key }
            update(Snapshot(branch, key, keepIssue, loading = true))
        }
        val fetchKey = key ?: return // checked above; the compiler can't see through synchronized
        runInBackground(
            project, null,
            work = { Clients.jira().issue(fetchKey) },
            onError = { e -> updateIfCurrent(request, Snapshot(branch, key, keepIssue, e.userMessage())) },
            onSuccess = { issue -> updateIfCurrent(request, Snapshot(branch, key, issue)) },
        )
    }

    private fun updateIfCurrent(request: Long, newSnapshot: Snapshot) = synchronized(this) {
        if (generation.get() == request) update(newSnapshot)
    }

    /** Listeners are always notified on the EDT, outside modal dialogs; git events arrive on background threads. */
    private fun update(newSnapshot: Snapshot) {
        snapshot = newSnapshot
        ApplicationManager.getApplication().invokeLater(
            { project.messageBus.syncPublisher(TOPIC).currentIssueChanged(newSnapshot) },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    override fun dispose() = Unit

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): CurrentIssueService = project.getService(CurrentIssueService::class.java)
    }
}
