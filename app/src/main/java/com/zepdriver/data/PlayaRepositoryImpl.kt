package com.zepdriver.data

import android.content.Context
import android.util.Log
import com.zepdriver.BuildConfig
import com.zepdriver.PlayaBundle
import com.zepdriver.PlayaRepository
import com.zepdriver.core.brc.BrcAddressing
import com.zepdriver.core.data.PlayaData
import com.zepdriver.core.data.PlayaDataLoader
import com.zepdriver.core.embargo.DataAccess
import com.zepdriver.core.rules.ruleSetFor
import com.zepdriver.core.search.SearchIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Loads the playa data bundle (built-in assets or the newest verified
 * download) and keeps it up to date from [BuildConfig.DATA_BASE_URL].
 *
 * What is shown follows the location embargo ([EmbargoControllerImpl]): the
 * previous year's city (this public bundle) until the new year's camps are
 * released, then the new year's sealed bundle with camps but no art, then
 * everything. Caught tampering shows no camps or art at all. Updates only
 * ever touch the public bundle; the sealed year ships inside the APK.
 *
 * Downloads live in `filesDir/playa/<year>/<version>/`; a version directory
 * only counts once its `.complete` marker exists, which is written after every
 * file's SHA-256 matched the manifest and the bundle parsed. Directories are
 * swapped in with a rename, and older copies are deleted only after the new one
 * has loaded.
 */
class PlayaRepositoryImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val embargo: EmbargoControllerImpl,
) : PlayaRepository {

    private val year = BuildConfig.DATA_YEAR
    private val assetDir = "playa/$year"
    private val rootDir = File(context.filesDir, "playa/$year")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _bundle = MutableStateFlow<PlayaBundle?>(null)
    override val bundle: StateFlow<PlayaBundle?> = _bundle.asStateFlow()

    private val _status = MutableStateFlow("Loading data…")
    override val updateStatus: StateFlow<String> = _status.asStateFlow()

    /** Version of the public bundle currently loaded. */
    @Volatile private var currentVersion: String? = null
    /** Directory of the loaded download, or null for the built-in assets. */
    @Volatile private var currentDir: File? = null

    /** The public (previous-year) bundle and where MapLibre reads its layers. */
    private var publicData: PlayaData? = null
    private var publicPrefix = ""
    /** The sealed year, opened on first need. */
    private var sealedData: PlayaData? = null
    /** What's published: access level plus the data version it came from. */
    private var published: Pair<DataAccess, String>? = null

    private val builtInVersion: String by lazy {
        runCatching { manifestOf(readAsset("manifest.json")).version }.getOrDefault("unknown")
    }

    @Serializable
    private data class BundleManifest(
        val year: Int = 0,
        val version: String,
        val files: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class InstallMarker(val installedAt: Long, val builtIn: String)

    /** Loads the best available bundle, then schedules update checks. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { loadBest() }
            embargo.state.map { it.access }.distinctUntilChanged().collect {
                mutex.withLock { publishForAccess() }
            }
        }
        DataUpdateWorker.schedule(context)
        DataUpdateWorker.runSoon(context)
    }

    override fun checkForUpdate() {
        scope.launch(Dispatchers.IO) {
            if (!checkNow()) DataUpdateWorker.runSoon(context)
        }
    }

    /**
     * Checks the online manifest and installs a newer bundle if there is one.
     * Returns false if the check should be retried (offline / network error).
     */
    suspend fun checkNow(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (publicData == null) loadBest()
            if (!Net.isOnline(context)) {
                _status.value = "Offline — will retry"
                return@withLock false
            }
            _status.value = "Checking…"
            try {
                val text = Net.getText("${BuildConfig.DATA_BASE_URL}$year/manifest.json")
                val remote = manifestOf(text)
                if (remote.year != 0 && remote.year != year) {
                    _status.value = "Up to date (${short(currentVersion)})"
                    return@withLock true
                }
                if (remote.version == currentVersion) {
                    _status.value = "Up to date (${short(currentVersion)})"
                    return@withLock true
                }
                install(remote, text)
                loadBest()
                if (currentVersion == remote.version) {
                    deleteOldCopies(keep = currentDir)
                    _status.value = "Updated to ${short(remote.version)}"
                } else {
                    _status.value = "Update failed — kept ${short(currentVersion)}"
                }
                true
            } catch (e: IOException) {
                Log.w(TAG, "update check failed", e)
                _status.value = "Offline — will retry"
                false
            } catch (e: Exception) {
                Log.w(TAG, "update rejected", e)
                _status.value = "Update failed: ${e.message ?: e.javaClass.simpleName}"
                true
            }
        }
    }

    // --- loading -------------------------------------------------------------

    /** Loads the newest complete download if valid, else the built-in assets. Caller holds [mutex]. */
    private fun loadBest() {
        for (dir in completeDownloads()) {
            if (currentDir == dir && publicData != null) return
            try {
                val data = PlayaDataLoader.load { path -> File(dir, path).readText() }
                setPublic(data, "file://${dir.absolutePath}/", dir)
                _status.value = "Downloaded data (${short(data.dataVersion)})"
                return
            } catch (e: Exception) {
                Log.w(TAG, "downloaded bundle $dir unusable, deleting", e)
                dir.deleteRecursively()
            }
        }
        if (currentDir == null && publicData != null) return
        try {
            val data = PlayaDataLoader.load { path -> readAsset(path) }
            setPublic(data, "asset://$assetDir/", null)
            _status.value = "Built-in data (${data.dataVersion.take(4)}…)"
        } catch (e: Exception) {
            Log.e(TAG, "built-in bundle failed to load", e)
            _status.value = "Data failed to load: ${e.message}"
        }
    }

    private fun setPublic(data: PlayaData, uriPrefix: String, dir: File?) {
        publicData = data
        publicPrefix = uriPrefix
        currentVersion = data.dataVersion
        currentDir = dir
        publishForAccess()
    }

    /** Publishes what the embargo allows right now. Caller holds [mutex]. */
    private fun publishForAccess() {
        val access = embargo.state.value.access
        val base = publicData ?: return
        val (data, prefix) = when (access) {
            DataAccess.NOTHING -> base.withPlaces(camps = false, art = false) to publicPrefix
            DataAccess.PREVIOUS_YEAR -> base to publicPrefix
            DataAccess.CAMPS, DataAccess.CAMPS_AND_ART -> {
                val sealed = embargo.sealed
                val year = sealed?.let { s ->
                    sealedData ?: try {
                        PlayaDataLoader.load(s::read).also { sealedData = it }
                    } catch (e: Exception) {
                        // Checked when the app started, so this is a changed asset: the controller
                        // only learns of it here. Show nothing rather than last year's places.
                        Log.e(TAG, "sealed ${s.year} data failed to open", e)
                        null
                    }
                }
                when {
                    sealed == null || year == null -> base.withPlaces(camps = false, art = false) to publicPrefix
                    access == DataAccess.CAMPS -> year.withPlaces(camps = true, art = false) to sealed.uriPrefix
                    else -> year to sealed.uriPrefix
                }
            }
        }
        val key = access to "${data.layout.year}/${data.dataVersion}/${data.camps.size}/${data.art.size}"
        if (key == published) return
        published = key
        publish(data, prefix)
    }

    private fun publish(data: PlayaData, uriPrefix: String) {
        val addressing = BrcAddressing(data)
        _bundle.value = PlayaBundle(
            data = data,
            ruleSet = ruleSetFor(data.layout.year),
            addressing = addressing,
            search = SearchIndex(data, addressing),
            uriPrefix = uriPrefix,
        )
    }

    /** Verified download directories, newest install first. Copies made for an older APK's data are dropped. */
    private fun completeDownloads(): List<File> {
        val dirs = rootDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val marker = File(dir, MARKER)
            val m = runCatching { json.decodeFromString(InstallMarker.serializer(), marker.readText()) }.getOrNull()
            when {
                m == null -> { dir.deleteRecursively(); null }
                // The APK was updated with different built-in data since this download: prefer the APK's.
                m.builtIn != builtInVersion -> { dir.deleteRecursively(); null }
                else -> dir to m.installedAt
            }
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun readAsset(path: String): String =
        context.assets.open("$assetDir/$path").bufferedReader().use { it.readText() }

    private fun manifestOf(text: String) = json.decodeFromString(BundleManifest.serializer(), text)

    // --- installing ----------------------------------------------------------

    private fun install(m: BundleManifest, manifestText: String) {
        require(m.files.isNotEmpty()) { "manifest lists no files" }
        require(m.version.matches(Regex("[A-Za-z0-9._-]+"))) { "bad version ${m.version}" }
        rootDir.mkdirs()
        val tmp = File(rootDir, ".tmp-${m.version}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val base = "${BuildConfig.DATA_BASE_URL}$year/"
            for ((path, sha) in m.files) {
                val dest = File(tmp, path)
                require(!path.startsWith("/") && ".." !in path.split('/') &&
                    dest.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "bad path $path" }
                val got = Net.download(base + path, dest)
                if (!got.equals(sha, ignoreCase = true)) throw SecurityException("checksum mismatch for $path")
            }
            File(tmp, "manifest.json").writeText(manifestText)
            // Parse once before accepting it.
            PlayaDataLoader.load { path -> File(tmp, path).readText() }
            File(tmp, MARKER).writeText(
                json.encodeToString(InstallMarker.serializer(), InstallMarker(System.currentTimeMillis(), builtInVersion))
            )
            val target = File(rootDir, m.version)
            if (target.exists() && target != currentDir) target.deleteRecursively()
            if (target.exists() || !tmp.renameTo(target)) throw IOException("could not install ${m.version}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun deleteOldCopies(keep: File?) {
        rootDir.listFiles()?.forEach { f -> if (f != keep) f.deleteRecursively() }
    }

    private fun short(version: String?) = version?.take(8) ?: "none"

    private companion object {
        const val TAG = "PlayaRepository"
        const val MARKER = ".complete"
    }
}
