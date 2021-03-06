// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.data

import com.intellij.concurrency.JobScheduler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.util.EventDispatcher
import org.jetbrains.annotations.CalledInAwt
import org.jetbrains.plugins.github.api.*
import org.jetbrains.plugins.github.api.data.GHPullRequestShort
import org.jetbrains.plugins.github.api.data.request.search.GithubIssueSearchType
import org.jetbrains.plugins.github.api.util.GithubApiSearchQueryBuilder
import org.jetbrains.plugins.github.api.util.SimpleGHGQLPagesLoader
import org.jetbrains.plugins.github.pullrequest.search.GithubPullRequestSearchQuery
import org.jetbrains.plugins.github.pullrequest.search.GithubPullRequestSearchQueryHolder
import org.jetbrains.plugins.github.pullrequest.ui.SimpleEventListener
import org.jetbrains.plugins.github.util.GithubAsyncUtil
import org.jetbrains.plugins.github.util.NonReusableEmptyProgressIndicator
import org.jetbrains.plugins.github.util.handleOnEdt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.ListModel
import javax.swing.event.ListDataListener
import kotlin.properties.Delegates

internal class GithubPullRequestsListLoaderImpl(private val progressManager: ProgressManager,
                                                private val requestExecutor: GithubApiRequestExecutor,
                                                private val serverPath: GithubServerPath,
                                                private val repoPath: GithubFullPath)
  : GithubPullRequestsListLoader, ListModel<GHPullRequestShort>, GithubPullRequestSearchQueryHolder, Disposable {

  override var searchQuery: GithubPullRequestSearchQuery
    by Delegates.observable(GithubPullRequestSearchQuery(emptyList())) { _, _, _ ->
      reset()
    }

  private val loader = SimpleGHGQLPagesLoader(requestExecutor, { p ->
    GHGQLRequests.PullRequest.search(serverPath, buildQuery(searchQuery), p)
  })
  private var lastFuture = CompletableFuture.completedFuture(emptyList<GHPullRequestShort>())
  private var progressIndicator = NonReusableEmptyProgressIndicator()

  override var loading: Boolean by Delegates.observable(false) { _, _, _ ->
    loadingStateChangeEventDispatcher.multicaster.eventOccurred()
  }

  private val listModelDelegate = CollectionListModel<GHPullRequestShort>()
  private var resetDisposable: Disposable

  override var error: Throwable? by Delegates.observable<Throwable?>(null) { _, _, _ ->
    errorChangeEventDispatcher.multicaster.eventOccurred()
  }

  override var outdated: Boolean by Delegates.observable(false) { _, _, newValue ->
    if (newValue) sizeChecker.stop()
    outdatedStateEventDispatcher.multicaster.eventOccurred()
  }

  private val loadingStateChangeEventDispatcher = EventDispatcher.create(SimpleEventListener::class.java)
  private val errorChangeEventDispatcher = EventDispatcher.create(SimpleEventListener::class.java)
  private val outdatedStateEventDispatcher = EventDispatcher.create(SimpleEventListener::class.java)

  private val sizeChecker = ListChangesChecker()

  init {
    requestExecutor.addListener(this) { reset() }

    Disposer.register(this, sizeChecker)

    resetDisposable = Disposer.newDisposable()
    Disposer.register(this, resetDisposable)
  }

  private fun buildQuery(searchQuery: GithubPullRequestSearchQuery?): String {
    return GithubApiSearchQueryBuilder.searchQuery {
      qualifier("type", GithubIssueSearchType.pr.name)
      qualifier("repo", repoPath.fullName)
      searchQuery?.buildApiSearchQuery(this)
    }
  }

  override fun canLoadMore() = !loading && (loader.hasNext || error != null)

  override fun loadMore() {
    val indicator = progressIndicator
    if (canLoadMore()) {
      loading = true
      requestLoadMore(indicator).handleOnEdt { list, error ->
        if (indicator.isCanceled) return@handleOnEdt
        when {
          error != null && !GithubAsyncUtil.isCancellation(error) -> {
            this.error = if (error is CompletionException) error.cause!! else error
          }
          list != null -> {
            listModelDelegate.add(list)
            sizeChecker.start()
          }
        }
        loading = false
      }
    }
  }

  private fun requestLoadMore(indicator: ProgressIndicator): CompletableFuture<List<GHPullRequestShort>> {
    lastFuture = lastFuture.thenApplyAsync {
      progressManager.runProcess(Computable { loader.loadNext(indicator) }, indicator)
    }
    return lastFuture
  }

  override fun reloadData(request: CompletableFuture<out GHPullRequestShort>) {
    request.handleOnEdt(resetDisposable) { result, error ->
      if (error == null && result != null) updateData(result)
    }
  }

  private fun updateData(pullRequest: GHPullRequestShort) {
    val index = listModelDelegate.items.indexOfFirst { it.id == pullRequest.id }
    listModelDelegate.setElementAt(pullRequest, index)
  }

  override fun getElementAt(index: Int): GHPullRequestShort = listModelDelegate.getElementAt(index)
  override fun getSize(): Int = listModelDelegate.size

  override fun reset() {
    loader.reset()
    lastFuture = lastFuture.handle { _, _ ->
      listOf<GHPullRequestShort>()
    }

    progressIndicator.cancel()
    progressIndicator = NonReusableEmptyProgressIndicator()
    error = null
    loading = false
    outdated = false
    sizeChecker.stop()

    listModelDelegate.removeAll()

    Disposer.dispose(resetDisposable)
    resetDisposable = Disposer.newDisposable()
    Disposer.register(this, resetDisposable)
  }

  override fun addListDataListener(l: ListDataListener) = listModelDelegate.addListDataListener(l)
  override fun removeListDataListener(l: ListDataListener) = listModelDelegate.removeListDataListener(l)

  override fun addLoadingStateChangeListener(disposable: Disposable, listener: () -> Unit) =
    SimpleEventListener.addDisposableListener(loadingStateChangeEventDispatcher, disposable, listener)

  override fun addErrorChangeListener(disposable: Disposable, listener: () -> Unit) =
    SimpleEventListener.addDisposableListener(errorChangeEventDispatcher, disposable, listener)

  override fun addOutdatedStateChangeListener(disposable: Disposable, listener: () -> Unit) =
    SimpleEventListener.addDisposableListener(outdatedStateEventDispatcher, disposable, listener)

  override fun dispose() = progressIndicator.cancel()

  private inner class ListChangesChecker : Disposable {

    private var scheduler: ScheduledFuture<*>? = null
    private var progressIndicator: ProgressIndicator? = null

    @Volatile
    private var lastETag: String? = null
      set(value) {
        if (field != null && value != null && field != value) runInEdt { outdated = true }
        field = value
      }

    @CalledInAwt
    fun start() {
      if (scheduler == null) {
        val indicator = NonReusableEmptyProgressIndicator()
        progressIndicator = indicator
        scheduler = JobScheduler.getScheduler().scheduleWithFixedDelay({
                                                                         try {
                                                                           lastETag = loadListETag(indicator)
                                                                         }
                                                                         catch (e: Exception) {
                                                                           //ignore
                                                                         }
                                                                       }, 0, 1, TimeUnit.MINUTES)
      }
    }

    private fun loadListETag(indicator: ProgressIndicator): String? =
      progressManager.runProcess(Computable {
        requestExecutor.execute(GithubApiRequests.Repos.PullRequests.getListETag(serverPath, repoPath))
      }, indicator)

    @CalledInAwt
    fun stop() {
      scheduler?.cancel(true)
      scheduler = null
      progressIndicator?.cancel()
      progressIndicator = NonReusableEmptyProgressIndicator()
      lastETag = null
    }

    override fun dispose() {
      scheduler?.cancel(true)
      progressIndicator?.cancel()
    }
  }
}