package ac.mdiq.podcini.storage.model

import io.github.xilinjia.krdb.types.RealmObject
import io.github.xilinjia.krdb.types.annotations.PrimaryKey

/** A durable journal: pending edits and backups survive both process death and restarts. */
class MediaTagState : RealmObject {
    @PrimaryKey var key: String = ""
    var episodeId: Long = 0
    var uri: String = ""
    var baseline: String = ""
    var desired: String = ""
    var previous: String = ""
    var external: String = ""
    var revision: Long = 0
    var status: String = TagSaveStatus.SAVED.name
    var error: String = ""
    var modified: Long = 0
    var size: Long = 0
    var generation: Long = 0
    var backupPath: String = ""
    var stagedPath: String = ""
    var originalHash: String = ""
    var stagedHash: String = ""
    var writing: Boolean = false
    var writeRevision: Long = 0
}

enum class TagSaveStatus { SAVED, PENDING, WAITING_FOR_FILE, NEEDS_ACCESS, CONFLICT, FAILED, UNSUPPORTED }

class TagUndo : RealmObject {
    @PrimaryKey var id: String = ""
    var created: Long = 0
    var snapshot: String = ""
}
