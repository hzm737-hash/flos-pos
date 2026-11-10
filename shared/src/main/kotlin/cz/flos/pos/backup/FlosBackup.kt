package cz.flos.pos.backup

import cz.flos.pos.KeyValueStore
import cz.flos.pos.data.FlosDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything on this till in one .zip: the database (sales, products, customers…),
 * all settings with the receipt counters, and the private files (EET certificate, attachments).
 *
 * A restore cannot replace the database while it is open, so [stage] only checks the file and
 * leaves it as [PENDING] next to the data; [applyPending] puts it in place at the next start,
 * before the database is opened (called first thing by the Android app and the Windows app).
 */
object FlosBackup {
    const val PENDING = "restore-pending.zip"
    private const val SETTINGS = "settings.properties"
    private const val DB_DIR = "db/"
    private const val FILES_DIR = "files/"

    /** The database file: in the data folder on Windows, in databases/ next to it on Android. */
    fun databaseFile(dataDir: File): File {
        val here = File(dataDir, FlosDatabase.FILE_NAME)
        return if (here.exists()) here else File(dataDir.parentFile, "databases/" + FlosDatabase.FILE_NAME)
    }

    private fun isDbFile(f: File) = f.name.startsWith(FlosDatabase.FILE_NAME)

    fun create(dataDir: File, settings: Map<String, String>): ByteArray {
        val db = databaseFile(dataDir)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The main file and its write-ahead log together, so nothing written lately is lost.
            listOf("", "-wal").map { File(db.path + it) }.filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(DB_DIR + f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            dataDir.walkTopDown().filter { it.isFile && !isDbFile(it) && it.name != PENDING && !it.name.endsWith(".before-restore") }.forEach { f ->
                zip.putNextEntry(ZipEntry(FILES_DIR + f.relativeTo(dataDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(SETTINGS))
            Properties().apply { putAll(settings) }.store(zip, "Flos")
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** Why [bytes] cannot be restored here, or null when it can. */
    fun check(bytes: ByteArray, dataDir: File): String? {
        val names = mutableMapOf<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.name == DB_DIR + FlosDatabase.FILE_NAME) names[e.name] = zip.readBytes().copyOf(100)
                    else if (e.name == SETTINGS) names[e.name] = ByteArray(0)
                }
            }
        }.onFailure { return "Soubor není záloha Flos." }
        val header = names[DB_DIR + FlosDatabase.FILE_NAME] ?: return "V záloze chybí databáze."
        if (SETTINGS !in names) return "V záloze chybí nastavení."
        if (String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return "Databáze v záloze je poškozená."
        // A backup from a newer Flos cannot be opened by this one (update the app first).
        val current = runCatching { RandomAccessFile(databaseFile(dataDir), "r").use { it.seek(60); it.readInt() } }.getOrNull()
        val backup = ((header[60].toInt() and 0xFF) shl 24) or ((header[61].toInt() and 0xFF) shl 16) or
            ((header[62].toInt() and 0xFF) shl 8) or (header[63].toInt() and 0xFF)
        if (current != null && backup > current) return "Záloha je z novější verze Flos. Nejdřív aktualizujte aplikaci."
        return null
    }

    /** Leaves a checked backup to be restored at the next start. */
    fun stage(bytes: ByteArray, dataDir: File) {
        File(dataDir, PENDING).writeBytes(bytes)
    }

    /**
     * Restores a staged backup, if there is one. Must run before the database is opened.
     * The database being replaced is kept as flos.db.before-restore.
     */
    fun applyPending(dataDir: File, store: KeyValueStore) {
        val pending = File(dataDir, PENDING)
        if (!pending.isFile) return
        try {
            val db = databaseFile(dataDir)
            val dbDir = db.parentFile.apply { mkdirs() }
            val settings = Properties()
            val incoming = mutableListOf<Pair<File, ByteArray>>()
            ZipInputStream(pending.inputStream().buffered()).use { zip ->
                generateSequence { zip.nextEntry }.filter { !it.isDirectory }.forEach { e ->
                    val name = e.name
                    when {
                        name == SETTINGS -> settings.load(ByteArrayInputStream(zip.readBytes()))
                        name.startsWith(DB_DIR) -> {
                            val file = name.removePrefix(DB_DIR)
                            if (file.startsWith(FlosDatabase.FILE_NAME) && '/' !in file) incoming += File(dbDir, file) to zip.readBytes()
                        }
                        name.startsWith(FILES_DIR) -> {
                            val target = File(dataDir, name.removePrefix(FILES_DIR)).canonicalFile
                            // Never write outside the data folder, whatever the zip says.
                            if (target.path.startsWith(dataDir.canonicalPath + File.separator)) incoming += target to zip.readBytes()
                        }
                    }
                }
            }
            if (incoming.none { it.first.name == FlosDatabase.FILE_NAME } || settings.isEmpty) return
            if (db.isFile) db.copyTo(File(db.path + ".before-restore"), overwrite = true)
            listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
            incoming.forEach { (f, data) -> f.parentFile?.mkdirs(); f.writeBytes(data) }
            store.put(settings.stringPropertyNames().associateWith { settings.getProperty(it) })
        } finally {
            pending.delete()
        }
    }
}
