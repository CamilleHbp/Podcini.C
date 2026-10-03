package ac.mdiq.podcini.storage.tags

import ac.mdiq.podcini.storage.model.FileTagValues
import java.io.File
import java.io.IOException

object NativeTagCodec {
    init { System.loadLibrary("podcini_tags") }
    private external fun readFields(path: String): Array<Array<String>>
    private external fun writeFields(path: String, fields: Array<Array<String>>)

    fun read(file: File): FileTagValues {
        val fields = readFields(file.absolutePath)
        if (fields.size != 4) throw IOException("Invalid metadata response")
        return FileTagValues(fields[0].toList(), fields[1].toList(), fields[2].toList(), fields[3].firstOrNull().orEmpty())
    }

    fun write(file: File, values: FileTagValues) {
        writeFields(file.absolutePath, arrayOf(values.genres.toTypedArray(), values.moods.toTypedArray(),
            values.tags.toTypedArray(), arrayOf(values.convention)))
        if (read(file) != values) throw IOException("Metadata verification failed")
    }
}
