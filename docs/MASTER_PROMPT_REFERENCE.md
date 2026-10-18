
# MASTER HANDOVER & CROSS-FILE WIRING DIRECTIVE (FINAL PRODUCTION PASS)

An exhaustive, line-by-line cross-file wiring audit of `post_refactor_engine_audit.md` uncovered **16 critical defects, compiler errors, concurrency races, and broken handoffs** across `DownloadEngine.kt`, `TurboDownloader.kt`, `YoutubeDlDownloader.kt`, `ProgressWatchdog.kt`, `TaskRuntimeRegistry.kt`, `UrlClassifier.kt`, `HostPolicyRegistry.kt`, `DownloadRepository.kt`, and `StreamValidator.kt`.

You must use the **4-Agent Adversarial Workflow** (Archaeologist -> Principal Engineer -> Adversarial QA -> Build Gatekeeper) to apply every single fix below, verify all cross-file interactions, and ensure zero compromises.

---

## 1. Complete List of 16 Cross-File Fixes to Implement

### Fix 1: Fix the `SocketTimeoutException` Inheritance Trap & Watchdog Interrupt Handling in `TurboDownloader.kt`
* **Root Cause:** In Java/Android, `java.net.SocketTimeoutException` extends `java.io.InterruptedIOException`. In `TurboDownloader.segmented()` -> `downloadPiece()`, your catch block wrote:
 `if (e is java.io.InterruptedIOException || e.message?.contains("Canceled", ignoreCase = true) == true || !coroutineContext.isActive) return false`
 Because `SocketTimeoutException` is an `InterruptedIOException`, **every single socket timeout immediately returned `false` on Attempt 1 without retrying**, broke out of the worker loop (`if (!downloadPiece(plan[idx])) break`), and aborted the entire segmented download! Furthermore, when the watchdog calls `TurboDownloader.cancelTask(taskId)` to unstick a stalled socket while the parent job is still active (`coroutineContext.isActive == true`), returning `false` aborted `TurboDownloader` instead of letting the piece retry.
* **Exact Implementation in `TurboDownloader.kt`:**
 1. Remove `fullyCancelledTaskIds.remove(taskId)` from the inner `finally` blocks of `segmented()` and `single()`. Keep `fullyCancelledTaskIds.remove(taskId)` ONLY in the outer `finally` block of `download()`.
 2. In `download()`, before falling back from `segmented()` to `single()`, guard on active state:
    ```kotlin
    val canFallbackToSingle = canCommit(taskId) && coroutineContext.isActive &&
        (!partFile.exists() || partFile.length() == 0L || landedBytes == 0L)
    ```
 3. In `segmented()` -> `downloadPiece()`, replace the `catch` blocks with:
    ```kotlin
    } catch (e: CancellationException) {
        if (canCommit(taskId)) {
            state.commit(plan, total, force = true)
        }
        throw e
    } catch (e: Exception) {
        // Only abort without retry if the coroutine itself was cancelled (user pause/cancel)
        // or the task was marked for full cancellation. A SocketTimeoutException (which extends
        // InterruptedIOException) or a watchdog socket reset while coroutineContext.isActive
        // MUST count as a normal retryable attempt!
        if (!coroutineContext.isActive || !canCommit(taskId)) {
            if (canCommit(taskId)) {
                state.commit(plan, total, force = true)
            }
            return false
        }
        attempt++
        failureMessage.compareAndSet(null, e.message ?: e.javaClass.simpleName)
        if (canCommit(taskId)) {
            state.commit(plan, total, force = true)
        }
    }
    ```

---

### Fix 2: Fix Hard Kotlin Compiler Error & Swallowed `CancellationException` in `YoutubeDlDownloader.kt` (`downloadMagnetAria2c`)
* **Root Cause:** In `downloadMagnetAria2c`, `fun runOnce(): File?` calls `coroutineContext.ensureActive()` and `coroutineContext[Job]` without being declared `suspend`. Also, its `catch (e: Exception)` catches and swallows `CancellationException`.
* **Exact Implementation in `YoutubeDlDownloader.kt` (`downloadMagnetAria2c`):**
 1. Change `fun runOnce(): File? {` to `suspend fun runOnce(): File? {`.
 2. Add an explicit `CancellationException` handler before `catch (e: Exception)` and remove the specific process instance in `finally`:
    ```kotlin
    } catch (e: CancellationException) {
        process.destroyForcibly()
        throw e
    } catch (e: Exception) {
        process.destroyForcibly()
        errors.append("run ").append(attempts).append(": ").append(e.message ?: e.javaClass.simpleName).append('\n')
        return null
    } finally {
        completionHandle?.dispose()
        activeNativeProcesses.remove(taskId, process)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
    ```

---

### Fix 3: Fix Android ICU Regex `PatternSyntaxException` Crash in `YoutubeDlDownloader.kt` (`downloadHttpAria2c`)
* **Root Cause:** `downloadHttpAria2c` wrote `Regex("""\((\d+)\%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)`. Android's ICU regex engine throws `PatternSyntaxException` on `\%` because `%` is not a regex metacharacter.
* **Exact Implementation:** Change `\%` to `%`:
 ```kotlin
 val fallbackRegex = Regex("""\((\d+)%\).*?DL:\s*([\d.]+[KMGT]?i?B)""", RegexOption.IGNORE_CASE)

Fix 4: Stop Swallowing downloadHttpAria2c Exceptions in YoutubeDlDownloader.download()
Root Cause: In YoutubeDlDownloader.download(), inside if (backend.equals("aria2c", true) && !isM3u8 && !isExtractorTask), the catch (e: Exception) block checks if (isDirectMedia || backend == "aria2c") return null. Because backend == "aria2c" is always true there, it swallows every downloadHttpAria2c exception and returns null, hiding HTTP 429/403 status messages from DownloadEngine's isRateLimitedError and parkInsteadOfFail logic!
Exact Implementation in YoutubeDlDownloader.kt: In YoutubeDlDownloader.download(), inside if (backend.equals("aria2c", true) && !isM3u8 && !isExtractorTask):
if (isDirectMedia || backend.equals("aria2c", ignoreCase = true)) {
   throw e
}
And in DownloadEngine.kt, wrap the YoutubeDlDownloader.download(..., backend = "aria2c") fallback call in a try / catch (e: Exception) (rethrowing CancellationException) that records the error message so errReason and isRateLimitedError see the exact aria2c failure cause if both TurboDownloader and aria2c fail.
Fix 5: Fix Process Orphaning on Timeout/Cancel in YoutubeDlDownloader.listTorrentFiles()
Root Cause: listTorrentFiles() runs blocking fetchProc.waitFor() and showProc.inputStream.bufferedReader().readText() inside withTimeoutOrNull. In Kotlin coroutines, withTimeoutOrNull cannot interrupt a blocked Process.waitFor() or Reader.readText() unless the process is destroyed when the timeout/cancellation fires.
Exact Implementation in YoutubeDlDownloader.kt (listTorrentFiles): For both fetchProc and showProc, attach a job completion hook inside withTimeoutOrNull:
val fetchOk = withTimeoutOrNull(45_000L) {
   val handle = coroutineContext[Job]?.invokeOnCompletion {
       if (fetchProc.isAlive) fetchProc.destroyForcibly()
   }
   try {
       kotlinx.coroutines.runInterruptible(Dispatchers.IO) { fetchProc.waitFor() }
   } finally {
       handle?.dispose()
   }
} ?: run {
   fetchProc.destroyForcibly()
   return null
}
And similarly for showProc:
val output = withTimeoutOrNull(15_000L) {
   val handle = coroutineContext[Job]?.invokeOnCompletion {
       if (showProc.isAlive) showProc.destroyForcibly()
   }
   try {
       kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
           showProc.inputStream.bufferedReader().use { it.readText() }
       }
   } finally {
       handle?.dispose()
   }
} ?: run {
   showProc.destroyForcibly()
   return null
}
Fix 6: Ensure parkForRestore Exists and Compiles in DownloadRepository.kt
Root Cause: DownloadRepository.initPersistence() calls .map { parkForRestore(it) }, which was omitted from DownloadRepository.kt in post_refactor_engine_audit.md.
Exact Implementation in DownloadRepository.kt: Verify parkForRestore is present in DownloadRepository.kt:
private fun parkForRestore(task: DownloadTask): DownloadTask {
   return when (task.status) {
       TaskStatus.DOWNLOADING,
       TaskStatus.RESOLVING,
       TaskStatus.VALIDATING,
       TaskStatus.QUEUED -> task.copy(
           status = TaskStatus.PAUSED,
           speedBytesPerSec = 0.0,
           etaSeconds = 0L,
           errorMessage = null,
           userPaused = true
       )
       else -> task.copy(speedBytesPerSec = 0.0, etaSeconds = 0L)
   }
}
Fix 7: Restore StreamValidator Pre-Flight in DownloadEngine.startTask()
Root Cause: StreamValidator.validateResult(streamUrl, hdrs) was accidentally deleted when rewriting the if (finalBackend == "aria2c" && !isMagnet) block in DownloadEngine.kt.
Exact Implementation in DownloadEngine.kt: At the start of if (finalBackend == "aria2c" && !isMagnet) (before launching TurboDownloader or aria2c), restore the pre-flight check:
if (!isSocial) {
   val skipPreflight = isProvablyDirectFile(streamUrl) || bytesLanded(currentTask) > 0L
   if (!skipPreflight) {
       var rejection = StreamValidator.validateResult(streamUrl, hdrs)
       if (rejection?.refreshable == true) {
           coroutineContext.ensureActive()
           val fresh = resolveStreamUrl(permUrl, currentTask.site, currentTask.quality ?: defaultQuality, allowCacheHit = false)
           if (!fresh.isNullOrBlank() && fresh != streamUrl &&
               (isDirectMediaUrl(fresh) || isProvablyDirectFile(fresh))) {
               streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
               refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
               uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
               hdrs = buildHeaders(refererToPass, uaToPass)
               rejection = StreamValidator.validateResult(streamUrl, hdrs)
               coroutineContext.ensureActive()
               repository.update(currentTask.id) { it.copy(directUrl = streamUrl) }
           }
       }
       rejection?.let {
           if (currentTask.downloadedBytes == 0L && bytesLanded(currentTask) == 0L && attemptCrossProviderFailover(currentTask)) {
               return@launch
           }
           throw PipelineError.ValidationFailed(it.reason)
       }
   }
}
Fix 8: Preserve Existing .aria2 Fallback Partials on Resume in DownloadEngine.startTask()
Root Cause: When a download falls back from TurboDownloader to aria2c, handoffTurboPrefixToAria2c(dest) renames dest.part -> dest and deletes dest.turbo, after which aria2c creates dest.aria2. If the user pauses and resumes, startTask() unconditionally runs TurboDownloader first—which sees no .part or .turbo, starts from 0 bytes, and overwrites the partial dest file!
Exact Implementation in DownloadEngine.kt (if (finalBackend == "aria2c" && !isMagnet)):
val dest = File(targetFolder, File(currentTask.filePath).name)
val partFile = File(dest.absolutePath + ".part")
val aria2ControlFile = File(dest.absolutePath + ".aria2")
val hasExistingAria2Partial = aria2ControlFile.exists() || (dest.exists() && dest.length() > 0L && !partFile.exists())
var aria2Error: String? = null

if (!hasExistingAria2Partial) {
   // Step 1: Primary execution via TurboDownloader
   com.anonrode.downloader.util.DebugLog.engine(
       "task=effectiveSockets url=${streamUrl.take(110)}"
   )
   var turboRes = TurboDownloader.download(
       url = streamUrl,
       dest = dest,
       headers = hdrs,
       configuredSockets = effectiveSockets,
       onProgress = progressCb,
       taskId = currentTask.id
   )

   // Step 2: Self-healing token refresh if CDN returned 401/403/404/410 or HTML decoy page
   if (turboRes is TurboDownloader.TurboResult.Failure && coroutineContext.isActive) {
       val tokenExpired = turboRes.httpStatus in listOf(401, 403, 404, 410) || turboRes.htmlPage
       if (tokenExpired && !isSocial) {
           val fresh = resolveStreamUrl(permUrl, currentTask.site, currentTask.quality ?: defaultQuality, allowCacheHit = false)
           if (!fresh.isNullOrBlank() && fresh != streamUrl) {
               streamUrl = com.anonrode.downloader.pipeline.UrlClassifier.stripPreviewParam(fresh)
               refererToPass = taskReferer ?: getRefererForUrl(streamUrl)
               uaToPass = taskUa ?: getUserAgentForUrl(streamUrl)
               hdrs = buildHeaders(refererToPass, uaToPass)
               repository.update(currentTask.id) { it.copy(directUrl = streamUrl) }

               turboRes = TurboDownloader.download(
                   url = streamUrl,
                   dest = dest,
                   headers = hdrs,
                   configuredSockets = effectiveSockets,
                   onProgress = progressCb,
                   taskId = currentTask.id
               )
           }
       }
   }
   turboResult = turboRes
   when (turboRes) {
       is TurboDownloader.TurboResult.Success -> producedFile = turboRes.file
       is TurboDownloader.TurboResult.Failure -> turboFailure = turboRes
   }
}

// Step 3: Run aria2c if resuming an existing .aria2 partial OR as a single fallback after non-429 Turbo failure
if (producedFile == null && turboFailure?.httpStatus != 429 && coroutineContext.isActive) {
   if (!hasExistingAria2Partial) {
       handoffTurboPrefixToAria2c(dest)
       com.anonrode.downloader.util.DebugLog.engine(
           "task=${currentTask.id} turbo failed (${turboFailure?.message}), falling back once to aria2c"
       )
   } else {
       com.anonrode.downloader.util.DebugLog.engine(
           "task=${currentTask.id} resuming existing aria2c partial for ${dest.name}"
       )
   }
   try {
       producedFile = YoutubeDlDownloader.download(
           context = context,
           taskId = currentTask.id,
           sourceUrl = streamUrl,
           targetDir = targetFolder,
           preferredFilename = dest.name,
           backend = "aria2c",
           referer = refererToPass,
           origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(refererToPass),
           customHeaders = hdrs,
           ua = uaToPass,
           parallelSockets = effectiveSockets,
           quality = currentTask.quality ?: defaultQuality,
           isExtractorTask = false,
           audioOnly = currentTask.audioOnly,
           onProgress = { dl, tot, spd, eta ->
               repository.updateProgress(currentTask.id, dl, tot, spd, eta)
               updateServiceState(force = false)
           },
           magnetMaxAttempts = magnetMaxAttempts,
           ytdlpMaxAttempts = ytdlpMaxAttempts,
           hlsFragments = hlsFragmentConcurrency,
           speedLimitKbs = globalSpeedLimitKbs,
           torrentPeers = torrentPeers,
           privacyMode = torrentPrivacyMode,
           estimatedTotal = repository.find(currentTask.id)?.totalBytes ?: currentTask.totalBytes
       )
   } catch (ce: CancellationException) {
       throw ce
   } catch (e: Exception) {
       aria2Error = e.message
   }
}
And when constructing errReason on failure, check aria2Error so rate-limit/HTTP errors from aria2c are preserved:
producedFile == null && !aria2Error.isNullOrBlank() -> aria2Error!!
Fix 9: Fix Stale task.filePath Snapshot Undoing Extension Synchronization in DownloadEngine.startTask()
Root Cause: Line 1324 updates filePath in the repository (e.g., from .mp4 to .mkv) and updates var currentTask. However, lines 1538, 1610, 1618, 1639, 1647, and 1711 still reference the original task.filePath parameter! At line 1618 (if (!isSocial && producedFile.name != File(task.filePath).name)), it renames the completed .mkv file back to .mp4!
Exact Implementation in DownloadEngine.kt: Change the parameter of startTask(initialTask: DownloadTask) and declare var task = repository.find(initialTask.id) ?: initialTask at the top of engineScope.launch. Whenever filePath is updated in the repository (at extension sync), reassign task = repository.find(task.id) ?: task.copy(filePath = updatedFile.absolutePath). Keep val originalFilePath = initialTask.filePath so sidecar cleanup at completion deletes sidecars for both producedFile.absolutePath, task.filePath, and originalFilePath.
Fix 10: Fix processQueue() \leftrightarrow startTask() Lock & Launch Race in DownloadEngine.kt
Root Cause:
processQueue() did not exclude tasks currently in activeIds when selecting nextTask = currentTasks.firstOrNull { it.status == TaskStatus.QUEUED && !it.userPaused }. If attemptCrossProviderFailover flips a task to QUEUED milliseconds before its coroutine's finally block finishes, processQueue() picks it, flips its status to RESOLVING, and then startTask() fails tryAcquireStartLock and returns—stranding the task in RESOLVING forever!
In startTask(), engineScope.launch starts executing on Dispatchers.IO before runtimeRegistry.attachJob(task.id, job) runs on the calling thread.
Exact Implementation in DownloadEngine.kt:
In processQueue():
val nextTask = currentTasks.firstOrNull {
   it.status == TaskStatus.QUEUED && !it.userPaused && it.id !in activeIds
} ?: return
And acquire the start lock inside processQueue() (or before flipping status) so status is never flipped to RESOLVING/DOWNLOADING unless the lock is held.
In startTask(): Launch the job with CoroutineStart.LAZY, register it in runtimeRegistry.attachJob(task.id, job) first, and then call job.start():
val job = engineScope.launch(start = CoroutineStart.LAZY) {
   ...
}
runtimeRegistry.attachJob(task.id, job)
val current = repository.find(task.id)
if (current == null || current.userPaused || current.status !in setOf(TaskStatus.RESOLVING, TaskStatus.DOWNLOADING, TaskStatus.VALIDATING)) {
   job.cancel()
   runtimeRegistry.remove(task.id)
} else {
   job.start()
}
Fix 11: Fix processQueue() Magnet Wi-Fi Gate Blocking Normal Downloads & Unpark on Settings Change
Root Cause:
In processQueue(), when wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:"), it parks the magnet task and executes return instead of continue—which prevents any normal HTTP tasks behind the magnet from starting!
In saveAllSettings(), when the user disables wifiOnlyAll or wifiOnlyTorrents, tasks already parked with "Waiting for Wi-Fi..." are never unparked.
Exact Implementation in DownloadEngine.kt:
In processQueue():
if (wifiOnlyAll && !net.isWifi) {
   currentTasks.filter { it.status == TaskStatus.QUEUED && !it.userPaused }.forEach { queued ->
       repository.update(queued.id) { t -> t.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Wi-Fi Only enabled)") }
   }
   return
}
if (wifiOnlyTorrents && !net.isWifi && nextTask.directUrl.startsWith("magnet:", ignoreCase = true)) {
   repository.update(nextTask.id) { it.copy(status = TaskStatus.PAUSED, errorMessage = "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)") }
   continue // Continue loop so non-torrent HTTP tasks behind this magnet can start!
}
In saveAllSettings(), before calling enforceConcurrencyLimit():
val net = networkObserver.getCurrentStatus()
if (net.isConnected) {
   repository.tasks.value.filter {
       it.status == TaskStatus.PAUSED && !it.userPaused && (
           (!this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Wi-Fi Only enabled)") ||
           (!this.wifiOnlyTorrents && !this.wifiOnlyAll && it.errorMessage == "Waiting for Wi-Fi (Torrents Wi-Fi Only enabled)")
       )
   }.forEach { t ->
       repository.update(t.id) { cur ->
           if (cur.userPaused || cur.status != TaskStatus.PAUSED) cur
           else cur.copy(status = TaskStatus.QUEUED, errorMessage = null)
       }
   }
}
Fix 12: Fix handleWatchdogStall Resetting Park Counters & Sync MAX_STALL_KILLS
Root Cause: In DownloadEngine.handleWatchdogStall, the else branch calls runtimeRegistry.markForManualRetry(taskId) on every non-exhausted stall kill, which calls resetParkingAndFailoverState() and wipes out parkCycleCount and firstParkedAtMs.
Exact Implementation:
In DownloadEngine.handleWatchdogStall, remove the else { runtimeRegistry.markForManualRetry(taskId) } branch completely.
In ProgressWatchdog.kt, set private const val MAX_STALL_KILLS = 8 (matching DownloadEngine's original budget: 3 initial tries + 3 re-resolved tries + headroom) and remove the dead duplicate constants CRAWL_WINDOW_BYTES, MAX_STALL_KILLS, and THROTTLE_SESSION_CAP_MS from DownloadEngine.companion.
Fix 13: Fix HostPolicyRegistry.effectiveSocketsForUrl Single-Socket Floor
Root Cause: minOf(matchedCap, requestedSockets.coerceAtLeast(2)) forces requestedSockets = 1 up to 2.
Exact Implementation in HostPolicyRegistry.kt:
fun effectiveSocketsForUrl(url: String, requestedSockets: Int): Int {
   val clampedRequest = requestedSockets.coerceIn(1, 16)
   if (clampedRequest == 1) return 1
   val host = UrlClassifier.extractHost(url)
   val matchedCap = SENSITIVE_SOCKET_CAP_HOSTS.entries
       .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
       ?.value
   return if (matchedCap != null) {
       minOf(matchedCap, clampedRequest)
   } else {
       clampedRequest
   }
}
Fix 14: Fix UrlClassifier Case-Insensitive/Valueless ?preview & Image Extensions
Root Cause: httpUrl.queryParameter("preview") is case-sensitive and returns null for valueless query params like ?preview&token=123. Also, DIRECT_MEDIA_EXTENSIONS omits image extensions (jpg, jpeg, png, webp) that DownloadEngine supports.
Exact Implementation in UrlClassifier.kt:
Add "jpg", "jpeg", "png", "webp" to DIRECT_MEDIA_EXTENSIONS.
In stripPreviewParam(rawUrl: String):
fun stripPreviewParam(rawUrl: String): String {
   val trimmed = rawUrl.trim()
   val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed
   val matchingNames = httpUrl.queryParameterNames.filter { it.equals("preview", ignoreCase = true) }
   if (matchingNames.isEmpty()) return trimmed
   val builder = httpUrl.newBuilder()
   matchingNames.forEach { builder.removeAllQueryParameters(it) }
   return builder.build().toString()
}
In extractMediaExtension(rawUrl: String), use httpUrl?.query (decoded) in addition to encodedQuery so URL-encoded filenames are matched cleanly.
Fix 15: Resume User-Paused Task When Re-Enqueued in DownloadEngine.enqueue()
Root Cause: In enqueue(), activeStatuses includes TaskStatus.PAUSED and returns existing.id without resuming. If a user taps "Download" on an episode that is currently PAUSED, nothing happens.
Exact Implementation in DownloadEngine.kt (enqueue): Remove TaskStatus.PAUSED from activeStatuses, and check TaskStatus.PAUSED alongside TaskStatus.FAILED:
val resumableExisting = currentTasks.firstOrNull {
   it.sourceUrl == sourceUrl && (it.status == TaskStatus.FAILED || it.status == TaskStatus.PAUSED)
}
if (resumableExisting != null) {
   retryTaskId = resumableExisting.id
   return@synchronized resumableExisting.id
}
Fix 16: Clean Up COMPLETED Registry Entries, Restrict TLS Bypass & Remove Residual Raw URL Slicing
Exact Implementation:
Clean up TaskRuntimeRegistry on completion (DownloadEngine.kt): In both TaskStatus.COMPLETED branches (verified and unverified), call runtimeRegistry.remove(task.id).
Restrict TLS Bypass (YoutubeDlDownloader.kt & StreamValidator.kt):
In YoutubeDlDownloader.kt (inside YoutubeDLRequest setup), replace unconditional addOption("--no-check-certificate") with:
if (HostPolicyRegistry.allowsPermissiveTlsFallback(sourceUrl)) {
   addOption("--no-check-certificate")
}
In StreamValidator.kt, change val tls = isTlsChainFailure(e) to:
val tls = isTlsChainFailure(e) && HostPolicyRegistry.allowsPermissiveTlsFallback(url)
Use UrlClassifier & HostPolicyRegistry everywhere in DownloadEngine.kt:
Delegate ASIAN_DRAMA_CLUSTER, WESTERN_CLUSTER, DEDICATED_ASIAN_SITES, and SECONDARY_ASIAN_FALLBACK to HostPolicyRegistry.
In purgeTaskArtifacts: use val urlExt = com.anonrode.downloader.pipeline.UrlClassifier.extractMediaExtension(task.directUrl) ?: "".
In preflightHls: use val host = com.anonrode.downloader.pipeline.UrlClassifier.extractHost(probeUrl).
In runYtdlp: use origin = com.anonrode.downloader.pipeline.UrlClassifier.extractOrigin(runReferer).
2. Mandatory Verification & Updated Specification Output
Apply all 16 fixes across the repository.
Verify all helper files (ProgressParser.kt, TurboState.kt, Aria2Control.kt, HlsSizeEstimator.kt, HlsMonotonicProgressReconciler.kt, FlickerFreeEtaCalculator.kt) are intact and compile cleanly with YoutubeDlDownloader.kt, TurboDownloader.kt, and DownloadEngine.kt.
Update post_refactor_engine_audit.md (max 350 KB) with the 100% full, unabridged source code of all modified files and the verification report.
"