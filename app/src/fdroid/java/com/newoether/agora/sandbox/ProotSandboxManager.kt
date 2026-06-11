package com.newoether.agora.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileSystems
class ProotSandboxManager(private val context: Context) : SandboxManager {

    private val rootfsDir: File = File(context.filesDir, "alpine-rootfs")

    private val prootExecPath: String by lazy {
        // Force System.loadLibrary to trigger extraction from APK.
        // Without this, the .so may not be in nativeLibraryDir at runtime.
        try { System.loadLibrary("agora_proot") } catch (_: Throwable) {}
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    override var lastError: String? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) { lastError = "rootfs not found: ${rootfsDir.absolutePath}"; return@withContext false }
        val sh = listOf("bin/sh", "usr/bin/sh").map { File(rootfsDir, it) }.any { it.exists() }
        val linker = listOf("lib/ld-musl-aarch64.so.1", "usr/lib/ld-musl-aarch64.so.1").map { File(rootfsDir, it) }.any { it.exists() }
        if (!sh) { lastError = "/bin/sh missing (symlinks not created?)"; return@withContext false }
        if (!linker) { lastError = "musl linker missing"; return@withContext false }
        true
    }

    override suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            val tmpTar = File(context.filesDir, "alpine-rootfs.tar.gz")
            try {
                val assetFiles = context.assets.list("") ?: emptyArray()
                val assetName = if (assetFiles.any { it == "alpine-minirootfs.tar" }) "alpine-minirootfs.tar" else "alpine-minirootfs.tar.gz"
                context.assets.open(assetName).use { input -> tmpTar.outputStream().use { output -> input.copyTo(output) } }

                if (assetName.endsWith(".tar")) {
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(tmpTar.inputStream()).use { tar -> extractTarEntries(tar, rootfsDir) }
                } else {
                    java.util.zip.GZIPInputStream(tmpTar.inputStream()).use { gz ->
                        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, rootfsDir) }
                    }
                }
            } finally { tmpTar.delete() }

            File(rootfsDir, "tmp").mkdirs()
            File(rootfsDir, "run").mkdirs()
            val rc = File(rootfsDir, "etc/resolv.conf"); rc.parentFile?.mkdirs()
            rc.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            // Ensure all binaries are executable (tar extraction may miss mode bits)
            listOf("bin", "usr/bin", "sbin", "usr/sbin").forEach { dir ->
                val d = File(rootfsDir, dir)
                if (d.isDirectory) d.listFiles()?.forEach { it.setExecutable(true) }
            }
            isAvailable()
        } catch (e: Throwable) { e.printStackTrace(); lastError = e.message; false }
    }

    override fun close() {}
    override suspend fun reset(): Boolean = withContext(Dispatchers.IO) { try { rootfsDir.deleteRecursively(); prootBin.delete(); true } catch (e: Throwable) { false } }

    // ── Shell Execution ─────────────────────────────────

    /** Path to proot binary, extracted from assets — Termux-style. */
    private val prootBin: File = File(context.filesDir, "bin/proot")

    private val prootPath: String by lazy {
        "${context.applicationInfo.nativeLibraryDir}/libproot_exec.so"
    }

    // Copy libtalloc.so -> libtalloc.so.2 in writable dir for linker resolution.
    // Android linker searches by exact filename, not SONAME.
    // Kai's proot DT_NEEDED is "libtalloc.so.2" but jniLibs has "libtalloc.so".
    private val tallocDir: File by lazy {
        File(context.filesDir, "lib").apply { mkdirs() }
    }
    private fun ensureTalloc(): String {
        val src = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
        val dst = File(tallocDir, "libtalloc.so.2")
        if (!dst.exists() && src.exists()) {
            src.copyTo(dst)
        }
        return tallocDir.absolutePath
    }

    private fun executeRaw(command: String, workdir: String = "/root", timeoutMs: Int = 30000): SandboxManager.SandboxResult {
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        // Kai-style CLI: --rootfs=, --bind=, -0
        val args = listOf(prootPath,
            "--rootfs=" + rootfsDir.absolutePath,
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "-w", workdir.ifBlank { "/root" },
            "-0",  // link2symlink extension
            "/bin/sh", "-c", command
        )
        return try {
            val libDir = context.applicationInfo.nativeLibraryDir
            val tallocLibDir = ensureTalloc()
            val ldPath = "$tallocLibDir:$libDir"
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
            pb.environment()["PROOT_TMP_DIR"] = tmpDir
            pb.environment()["HOME"] = "/root"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!ok) { p.destroyForcibly(); SandboxManager.SandboxResult(out, "Timed out", -1) }
            else SandboxManager.SandboxResult(out, "", p.exitValue())
        } catch (e: Throwable) { SandboxManager.SandboxResult("", e.message ?: "proot failed", -1) }
    }

    override suspend fun executeCommand(cmd: String, wd: String, to: Int): SandboxManager.SandboxResult {
        if (!isAvailable()) return SandboxManager.SandboxResult("", "Sandbox not installed", -1)
        return executeRaw(cmd, wd.ifBlank { "/root" }, to)
    }

    // ── File Operations ────────────────────────────────

    override suspend fun fileRead(path: String, offset: Long, limit: Long): String = withContext(Dispatchers.IO) {
        val f = resolvePath(path); if (!f.exists()) throw IllegalStateException("File not found: $path")
        val b = f.readBytes(); val s = offset.coerceIn(0, b.size.toLong()).toInt()
        val e = if (limit > 0) minOf(s + limit, b.size.toLong()).toInt() else b.size
        String(b, s, e - s, Charsets.UTF_8)
    }

    override suspend fun fileWrite(path: String, content: String): String? = withContext(Dispatchers.IO) {
        try { val f = resolvePath(path); f.parentFile?.mkdirs(); f.writeText(content, Charsets.UTF_8); null }
        catch (e: Throwable) { "Sandbox file write failed: ${e.message}" }
    }

    override suspend fun fileGlob(pattern: String, basePath: String): List<String> = withContext(Dispatchers.IO) {
        val base = resolvePath(if (basePath.isBlank()) "/" else basePath)
        val files = mutableListOf<String>(); walkFiles(base, files, rootfsDir.absolutePath)
        globMatch(files, rootfsDir.absolutePath, pattern)
    }

    override suspend fun fileGrep(pattern: String, basePath: String, fileGlob: String): Result<List<SandboxManager.GrepMatch>> = withContext(Dispatchers.IO) {
        try {
            val regex = try { Regex(pattern) } catch (e: Throwable) { Regex(java.util.regex.Pattern.quote(pattern)) }
            val files = if (fileGlob.isNotBlank()) fileGlob(fileGlob, basePath)
            else { val b = resolvePath(if (basePath.isBlank()) "/" else basePath); val a = mutableListOf<String>(); walkFiles(b, a, rootfsDir.absolutePath); a }
            val matches = mutableListOf<SandboxManager.GrepMatch>()
            for (file in files) {
                try {
                    val resolved = if (file.startsWith("/")) resolvePath(file) else resolvePath("/$file")
                    if (!resolved.exists() || resolved.length() > 500_000L) continue
                    resolved.readText(Charsets.UTF_8).lines().forEachIndexed { i, line ->
                        if (regex.containsMatchIn(line)) matches.add(SandboxManager.GrepMatch(path = file, line = i + 1, content = line.take(500)))
                    }
                } catch (_: Throwable) {}
            }
            Result.success(matches)
        } catch (e: Throwable) { Result.failure(e) }
    }

    override suspend fun fileEdit(path: String, oldString: String, newString: String, replaceAll: Boolean): SandboxManager.FileEditResult = withContext(Dispatchers.IO) {
        try {
            val f = resolvePath(path); if (!f.exists()) return@withContext SandboxManager.FileEditResult(0, "File not found: $path")
            val content = f.readText(Charsets.UTF_8); val count = content.split(oldString).size - 1
            if (count == 0) SandboxManager.FileEditResult(0, "old_string not found in file")
            else if (count > 1 && !replaceAll) SandboxManager.FileEditResult(0, "Found $count matches. Use replace_all=true.")
            else { f.writeText(content.replace(oldString, newString), Charsets.UTF_8); SandboxManager.FileEditResult(if (replaceAll) count else 1) }
        } catch (e: Throwable) { SandboxManager.FileEditResult(0, "Sandbox file edit failed: ${e.message}") }
    }

    // ── Package Management ──────────────────────────────

    private val lastPkgSamples = mutableListOf<String>()

    override suspend fun apkInstall(packageName: String, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val mirror = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64"
        val indexUrl = "$mirror/APKINDEX.tar.gz"

        onProgress("Downloading APKINDEX...")
        val indexFile = File(context.filesDir, "APKINDEX.tar.gz")
        try { URL(indexUrl).openStream().use { i -> indexFile.outputStream().use { o -> i.copyTo(o) } } }
        catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "APKINDEX: ${e.message}"; return@withContext false }

        onProgress("Parsing APKINDEX for $packageName...")
        val pkgFileName = findPkgInIndex(indexFile, packageName)
        indexFile.delete()
        if (pkgFileName == null) {
            val s = lastPkgSamples.joinToString(", ")
            onProgress("NOT FOUND${if (s.isNotEmpty()) ". Similar: $s" else ""}")
            lastError = if (s.isNotEmpty()) "'$packageName' not found. Available: $s" else "'$packageName' not found"
            lastPkgSamples.clear(); return@withContext false
        }
        lastPkgSamples.clear()
        onProgress("Found: $pkgFileName")

        val pkgUrl = "$mirror/$pkgFileName"
        val pkgFile = File(context.filesDir, pkgFileName)
        onProgress("Downloading $pkgFileName...")
        try {
            val conn = URL(pkgUrl).openConnection() as HttpURLConnection
            val total = conn.contentLength
            conn.inputStream.use { i -> pkgFile.outputStream().use { o ->
                    val buf = ByteArray(8192); var dl = 0L; var lr = 0L
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); dl += n
                        if (dl - lr > 50000 || dl == total.toLong()) { lr = dl; onProgress("  ${if (total > 0) "${dl * 100 / total}%" else "?"} (${dl / 1024}KB)") }
                    }
            } }
        } catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "Download: ${e.message}"; return@withContext false }
        onProgress("Downloaded ${pkgFile.length() / 1024}KB")

        onProgress("Extracting $pkgFileName...")
        try { extractApk(pkgFile, rootfsDir) }
        catch (e: Throwable) { onProgress("FAIL: ${e.message}"); lastError = "Extract: ${e.message}"; pkgFile.delete(); return@withContext false }
        pkgFile.delete()
        onProgress("Extracted successfully")

        updateInstalledDb(rootfsDir, packageName)
        onProgress("Package database updated")
        true
    }

    override suspend fun apkList(): List<SandboxManager.PackageInfo> = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext emptyList()
        try {
            val db = File(rootfsDir, "lib/apk/db/installed")
            if (!db.exists()) return@withContext emptyList()
            val pkgs = mutableListOf<SandboxManager.PackageInfo>()
            var n = ""; var v = ""; var d = ""
            db.readLines(Charsets.UTF_8).forEach { line ->
                when { line.startsWith("P:") -> n = line.substring(2).trim(); line.startsWith("V:") -> v = line.substring(2).trim()
                    line.startsWith("T:") -> d = line.substring(2).trim()
                    line.isBlank() && n.isNotBlank() -> { pkgs.add(SandboxManager.PackageInfo(name = n, version = v, description = d)); n = ""; v = ""; d = "" } }
            }; pkgs
        } catch (e: Throwable) { emptyList() }
    }

    // ── Tar Extraction ──────────────────────────────────

    private fun extractTarEntries(tar: org.apache.commons.compress.archivers.tar.TarArchiveInputStream, destDir: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        var entry = tar.nextEntry
        while (entry != null) {
            val outFile = File(destDir, entry.name)
            when {
                entry.isDirectory -> outFile.mkdirs()
                entry.isSymbolicLink -> { outFile.parentFile?.mkdirs(); symlinks.add(entry.name to entry.linkName) }
                entry.isFile -> { outFile.parentFile?.mkdirs(); outFile.outputStream().use { tar.copyTo(it) }; if (entry.mode and 0x40 != 0) outFile.setExecutable(true, false) }
            }
            entry = tar.nextEntry
        }
        for ((name, target) in symlinks) {
            val outFile = File(destDir, name); if (outFile.exists()) continue
            val src = File(destDir, target)
            if (!src.exists()) continue
            try {
                if (src.isDirectory) src.walkTopDown().forEach { f -> val rel = f.relativeTo(src).path; val dst = File(outFile, rel); if (f.isDirectory) dst.mkdirs() else { dst.parentFile?.mkdirs(); f.copyTo(dst, true) } }
                else { outFile.parentFile?.mkdirs(); src.copyTo(outFile, true) }
            } catch (_: Throwable) {}
        }
    }

    // ── APK Extraction ──────────────────────────────────

    private fun extractApk(apkFile: File, destDir: File) {
        val bytes = apkFile.readBytes(); var gzStart = -1; var gzCount = 0
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x1F.toByte() && bytes[i + 1] == 0x8B.toByte()) { gzCount++; if (gzCount == 2) { gzStart = i; break } }
        }
        if (gzStart < 0) throw java.io.IOException("Cannot find data.tar.gz in .apk")
        java.util.zip.GZIPInputStream(bytes.copyOfRange(gzStart, bytes.size).inputStream()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar -> extractTarEntries(tar, destDir) }
        }
    }

    private fun updateInstalledDb(rootfsDir: File, packageName: String) {
        File(rootfsDir, "lib/apk/db/installed").appendText("\nP:$packageName\nV:1.0\n\n", Charsets.UTF_8)
    }

    // ── APKINDEX Parsing ────────────────────────────────

    private fun findPkgInIndex(indexFile: File, packageName: String): String? {
        lastPkgSamples.clear()
        java.util.zip.GZIPInputStream(indexFile.inputStream()).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == "APKINDEX") {
                        val content = tar.readBytes().toString(Charsets.UTF_8); val lines = content.lines()
                        for (i in lines.indices) {
                            val line = lines[i].trim()
                            if (line.startsWith("P:") && lastPkgSamples.size < 10 && line.substring(2).startsWith(packageName.take(2))) lastPkgSamples.add(line.substring(2))
                            if (line == "P:$packageName") {
                                var version = ""
                                for (j in i + 1 until minOf(i + 20, lines.size)) { val next = lines[j].trim(); if (next.startsWith("C:")) break; if (next.startsWith("V:")) { version = next.substring(2).trim(); break } }
                                if (version.isNotEmpty()) return "$packageName-$version.apk"
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return null
    }

    // ── Helpers ────────────────────────────────────────

    private fun resolvePath(path: String): File {
        val resolved = File(rootfsDir, path.trimStart('/')).canonicalFile
        require(resolved.absolutePath.startsWith(rootfsDir.canonicalFile.absolutePath)) { "Path traversal: $path" }
        return resolved
    }

    private fun walkFiles(dir: File, result: MutableList<String>, rootFsAbsPath: String) {
        try { dir.listFiles()?.forEach { if (it.isDirectory) walkFiles(it, result, rootFsAbsPath) else result.add(it.absolutePath.removePrefix(rootFsAbsPath).removePrefix("/")) } } catch (_: Throwable) {}
    }

    private fun globMatch(files: List<String>, basePath: String, pattern: String): List<String> {
        val adj = if (pattern.contains('/')) pattern else "**/$pattern"
        val matcher = try { FileSystems.getDefault().getPathMatcher("glob:$basePath/$adj") } catch (_: Throwable) { return emptyList() }
        return files.filter { f -> val full = if (f.startsWith("/")) f else "$basePath/$f"; try { matcher.matches(java.nio.file.Paths.get(full)) } catch (_: Throwable) { false } }
    }
}
