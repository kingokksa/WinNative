package com.winlator.cmod.runtime.display

import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Warms the kernel page cache for a game's hot files BEFORE the Wine guest starts,
 * so the guest's LoadLibrary / mmap of Unity _Data/Managed DLLs and the
 * MonoBleedingEdge tree hits warm pages instead of slow FUSE external-storage reads.
 *
 * This deliberately does NOT move or copy the game — the game stays in the user-visible
 * external directory (for example /storage/emulated/0/...), preserving the "drop a folder
 * and play" advantage. We only touch the files (read them into the page cache) ahead of time.
 *
 * The warm runs best-effort on a background thread in parallel with the rest of the
 * launch prep; it never blocks the launch and is bounded by count/bytes/time budgets.
 */
object GameHotFileWarm {
    private const val TAG = "GameHotFileWarm"

    // Hard safety caps so we never scan/pin the whole device into memory.
    private const val MAX_FILES = 4000
    private const val MAX_BYTES = 512L * 1024 * 1024 // 512 MB
    private const val MAX_MILLIS = 40_000L
    private const val MAX_DEPTH = 8

    /** Extension patterns considered "hot" for a Unity/Mono game. */
    private val HOT_EXT = setOf("dll", "dll.dll", "exe", "config", "dat")

    data class WarmResult(
        val filesWarmed: Int,
        val bytesWarmed: Long,
        val elapsedMs: Long,
        val reason: String,
    )

    /**
     * Warm page cache for the given game directory. Best-effort: any failure is swallowed
     * and only logged, and a cap hit simply stops early.
     *
     * @param gameDir absolute path of the game folder (e.g. /storage/emulated/0/PC_GAME/X)
     * @return a small summary for logging / measurement.
     */
    fun warm(gameDir: File?): WarmResult {
        val start = System.currentTimeMillis()
        if (gameDir == null || !gameDir.isDirectory) {
            return WarmResult(0, 0, 0, "no-game-dir")
        }

        // Don't run on a path that is itself on internal app storage — nothing to gain.
        // Heuristic: treat paths under the app-private files dir as already-fast.
        if (isInternalStorage(gameDir)) {
            return WarmResult(0, 0, System.currentTimeMillis() - start, "internal-storage")
        }

        val hotFiles = ArrayList<File>()
        collectHotFiles(gameDir, hotFiles, 0, false)
        if (hotFiles.isEmpty()) {
            return WarmResult(0, 0, System.currentTimeMillis() - start, "no-hot-files")
        }

        // Sort by path so reads are sequentially-locality friendly on the backing store.
        hotFiles.sortBy { it.path }

        var warmed = 0
        var bytes = 0L
        val buf = ByteArray(256 * 1024)
        for (f in hotFiles) {
            if (warmed >= MAX_FILES || bytes >= MAX_BYTES) break
            if (System.currentTimeMillis() - start > MAX_MILLIS) break
            try {
                FileInputStream(f).use { ins ->
                    var read: Int
                    while (ins.read(buf).also { read = it } != -1) {
                        bytes += read
                    }
                }
                warmed++
            } catch (e: Exception) {
                // Unreadable / vanished file — ignore.
            }
        }

        val elapsed = System.currentTimeMillis() - start
        val reason = if (warmed >= MAX_FILES) "capped-files"
        else if (bytes >= MAX_BYTES) "capped-bytes"
        else if (elapsed > MAX_MILLIS) "capped-time"
        else "complete"
        Log.i(
            TAG,
            "warm finished: files=$warmed bytes=$bytes elapsed=${elapsed}ms reason=$reason " +
                "gameDir=${gameDir.absolutePath}",
        )
        return WarmResult(warmed, bytes, elapsed, reason)
    }

    private fun isInternalStorage(dir: File): Boolean {
        val path = dir.absolutePath.replace('\\', '/')
        // App-private internal storage (/data/...) and app-scoped external (/Android/data/...)
        // are real ext4/f2fs backed by the kernel page cache — already fast, nothing to warm.
        // The shared FUSE mounts (/storage/emulated/N, /mnt/...) are the slow path we warm.
        if (path.startsWith("/data/")) return true
        if (path.contains("/Android/data/")) return true
        return false
    }

    private fun collectHotFiles(dir: File, out: MutableList<File>, depth: Int, forceDescend: Boolean) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (out.size >= MAX_FILES) return
            if (c.isDirectory) {
                val lower = c.name.lowercase()
                val hotDir = lower == "managed" ||
                    lower.endsWith("_data") ||
                    lower.contains("mono") ||
                    lower.startsWith("mono")
                // Descend into known-hot dirs, or shallowly into anything near the game root.
                if (forceDescend || hotDir || depth < 2) {
                    collectHotFiles(c, out, depth + 1, forceDescend || hotDir)
                }
            } else {
                if (isHotFileName(c.name.lowercase())) {
                    out.add(c)
                }
            }
        }
    }

    private fun isHotFileName(name: String): Boolean {
        // Match Unity managed assemblies under _Data/Managed and the MonoBleedingEdge tree,
        // plus native plugin DLLs/exes, but only for dll/exe/config/dat extensions.
        for (ext in HOT_EXT) {
            if (name.endsWith("." + ext)) return true
        }
        return false
    }
}
