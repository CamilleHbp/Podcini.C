package ac.mdiq.podcini.config.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PreparedDatabaseRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `preparing a restore does not change the live database`() {
        val database = temporary.newFile("Podcini.realm").apply { writeText("current library") }
        val staged = temporary.newFile("staged.realm").apply { writeText("imported library with updated media paths") }
        PreparedDatabaseRestore(database).prepare(staged)
        assertEquals("current library", database.readText())
        assertFalse(staged.exists())
    }

    @Test fun `a fresh process installs the prepared database once`() {
        val database = temporary.newFile("Podcini.realm").apply { writeText("current library") }
        val staged = temporary.newFile("staged.realm").apply { writeText("restored library") }
        PreparedDatabaseRestore(database).prepare(staged)
        PreparedDatabaseRestore(database).install()
        assertEquals("restored library", database.readText())
        database.writeText("new listening progress")
        PreparedDatabaseRestore(database).install()
        assertEquals("new listening progress", database.readText())
    }

    @Test fun `failed or unfinished preparation leaves the current library intact`() {
        val database = temporary.newFile("Podcini.realm").apply { writeText("current library") }
        val unfinished = temporary.newFile("unfinished.realm")
        assertThrows(IOException::class.java) { PreparedDatabaseRestore(database).prepare(unfinished) }
        PreparedDatabaseRestore(database).install()
        assertEquals("current library", database.readText())
    }

    @Test fun `a second restore cannot replace one waiting for restart`() {
        val database = temporary.newFile("Podcini.realm").apply { writeText("current") }
        val first = temporary.newFile("first.realm").apply { writeText("first") }
        val second = temporary.newFile("second.realm").apply { writeText("second") }
        PreparedDatabaseRestore(database).prepare(first)
        assertThrows(IllegalStateException::class.java) { PreparedDatabaseRestore(database).prepare(second) }
        PreparedDatabaseRestore(database).install()
        assertEquals("first", database.readText())
    }

    @Test fun `failed activation preserves the prepared database for retry`() {
        val database = File(temporary.root, "Podcini.realm")
        database.mkdir()
        File(database, "obstruction").writeText("keep")
        val staged = temporary.newFile("staged.realm").apply { writeText("restored") }
        val restore = PreparedDatabaseRestore(database)
        restore.prepare(staged)
        assertThrows(IOException::class.java) { restore.install() }
        assertEquals("keep", File(database, "obstruction").readText())
        database.deleteRecursively()
        restore.install()
        assertEquals("restored", database.readText())
    }
}
