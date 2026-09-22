package ac.mdiq.podcini.storage.specs

import ac.mdiq.podcini.R

enum class Rating(val code: Int, val res: Int) {
    UNRATED(-3, R.drawable.baseline_thumb_right_off_alt_24),
    TRASH(-2, R.drawable.ic_delete),
    BAD(-1, R.drawable.baseline_thumb_down_24),
    OK(0, R.drawable.baseline_sentiment_neutral_24),
    GOOD(1, R.drawable.baseline_thumb_up_24),
    SUPER(2, R.drawable.ic_star);

    val labelRes: Int get() = when (this) {
        UNRATED -> R.string.unrated
        TRASH -> R.string.trash
        BAD -> R.string.bad
        OK -> R.string.OK
        GOOD -> R.string.good
        SUPER -> R.string.Super
    }

    companion object {
        fun fromCode(code: Int): Rating {
            return Rating.entries.firstOrNull { it.code == code } ?: UNRATED
        }
    }
}