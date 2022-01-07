package tests

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.machiav3lli.backup.actions.BackupAppAction
import com.machiav3lli.backup.actions.RestoreAppAction
import com.machiav3lli.backup.activities.MainActivityX
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.items.RootFile
import com.machiav3lli.backup.items.StorageFile
import com.topjohnwu.superuser.ShellUtils.fastCmd
import org.junit.*
import org.junit.Assert.assertEquals
import timber.log.Timber

class Test_BackupRestore {

    companion object {
        private val qUtilBox = ShellHandler.utilBoxQuoted
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        lateinit var tempDir: RootFile
        lateinit var testDir: RootFile

        var backupCreated = false
        var contentLs = ""

        @BeforeClass @JvmStatic
        fun setupClass() {
            //tempDir = RootFile(context.cacheDir, "test_backup_restore")
            tempDir = RootFile(context.dataDir, "test_backup_restore")
            tempDir.deleteRecursive()
            tempDir.mkdirs()
            //tempDir = RootFile("/data/local/tmp/test_backup_restore")
            testDir = prepareDirectory()
        }

        @AfterClass @JvmStatic
        fun cleanupClass() {
            tempDir.deleteRecursive()
        }

        fun listContent(dir: RootFile): String {
            return runAsRoot("ls -bAlZ ${dir.quoted} | grep -v total").out.joinToString("\n")
        }

        private fun prepareDirectory(): RootFile {

            val dir = RootFile(tempDir, "test_data")
            dir.mkdirs()

            val files = RootFile(dir, "files")
            files.mkdirs()

            val fifo = RootFile(dir, "fifo")
            fastCmd("$qUtilBox mkfifo ${fifo.quoted}")

            val symLinkDirRel = RootFile(dir, "link_sym_dir_rel")
            fastCmd("cd ${dir.quoted} && $qUtilBox ln -s ${files.name} ${symLinkDirRel.name}")

            val symLinkDirAbs = RootFile(dir, "link_sym_dir_abs")
            fastCmd("$qUtilBox ln -s ${files.absolutePath} ${symLinkDirAbs.quoted}")

            contentLs = listContent(dir)
            Timber.i(contentLs)

            return dir
        }
    }

    @get:Rule
    var activityRule = ActivityScenarioRule(MainActivityX::class.java)

    lateinit var tempStorage : StorageFile
    lateinit var shellHandler : ShellHandler
    lateinit var backupDirTarApi : StorageFile
    lateinit var backupDirTarCmd : StorageFile
    lateinit var archiveTarApi : StorageFile
    lateinit var archiveTarCmd : StorageFile
    lateinit var restoreCache : RootFile

    @Before
    fun init() {
        tempStorage = StorageFile(tempDir)
        shellHandler = ShellHandler()
        backupDirTarApi = tempStorage.createDirectory("backup_tarapi")
        backupDirTarCmd = tempStorage.createDirectory("backup_tarcmd")
        archiveTarApi = StorageFile(backupDirTarApi, "data.tar.gz")
        archiveTarCmd = StorageFile(backupDirTarCmd, "data.tar.gz")
        restoreCache = RootFile(tempDir, "cache").also { it.mkdirs() }
    }

    fun checkDirectory(dir: RootFile) {
        assertEquals(
          contentLs,
          listContent(dir)
        )
    }

    @Test
    fun test_directoryContents() {
        checkDirectory(testDir)
    }

    fun backup() {
        if(!backupCreated) {
            val backupAction = BackupAppAction(context, shellHandler)
            val fromDir = StorageFile(testDir)
            backupAction.genericBackupDataTarApi(
                "data", backupDirTarApi, fromDir.toString(),
                true, null
            )
            backupAction.genericBackupDataTarCmd(
                "data", backupDirTarCmd, fromDir.toString(),
                true, null
            )
            backupCreated = true
        }
    }

    fun listTar(archive: StorageFile): String {
      return runAsRoot(
          "tar -tvzf ${quote(archive.file!!.absolutePath)} | awk '{if($8) {print $1,$2,$3,$6,$7,$8} else {print $1,$2,$3,$6}}'"
      ).out
          .filterNotNull()
          .sorted()
          .joinToString("\n")
          .replace(Regex("""/$""", RegexOption.MULTILINE), "")
    }

    @Test
    fun test_backup() {
        backup()
        val expected =
            """
            drwxr-xr-x root/root 0 files
            lrwxrwxrwx root/root 0 link_sym_dir_abs -> <ABSLINK>
            lrwxrwxrwx root/root 0 link_sym_dir_rel -> files
            prw-r--r-- root/root 0 fifo
            """.trimIndent().replace("<ABSLINK>", RootFile(testDir, "files").absolutePath)
        assertEquals(
            expected,
            listTar(archiveTarApi)
        )
        assertEquals(
            expected,
            listTar(archiveTarCmd)
        )
    }

    fun checkRestore(toType: String, fromType: String) {
        backup()
        val archive = if(fromType == "tarapi")
                            archiveTarApi
                        else
                            archiveTarCmd
        val restoreAction = RestoreAppAction(context, shellHandler)
        val restoreDir = RootFile(tempDir, "restore_" + toType)
        restoreDir.mkdirs()
        if(toType == "tarapi")
            restoreAction.genericRestoreFromArchiveTarApi(
                "data", archive, restoreDir.toString(),
                true, false, null, restoreCache
            )
        else
            restoreAction.genericRestoreFromArchiveTarCmd(
                "data", archive, restoreDir.toString(),
                true, false, null
            )
        checkDirectory(restoreDir)
    }

    @Test fun test_restore_tarapi_from_tarapi() { checkRestore("tarapi", "tarapi") }
    @Test fun test_restore_tarcmd_from_tarapi() { checkRestore("tarcmd", "tarapi") }
    @Test fun test_restore_tarapi_from_tarcmd() { checkRestore("tarapi", "tarcmd") }
    @Test fun test_restore_tarcmd_from_tarcmd() { checkRestore("tarcmd", "tarcmd") }
}