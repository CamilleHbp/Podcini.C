package ac.mdiq.podcini.storage.model

import ac.mdiq.podcini.shared.nowInMillis
import ac.mdiq.podcini.storage.specs.EpisodeState

const val DEFAULT_COMPLETION_PERCENT = 97

fun completionReached(position: Int, duration: Int, threshold: Int): Boolean =
    duration > 0 && position.toLong() * 100 >= duration.toLong() * threshold.coerceIn(1, 100)

/** Furthest media position reached, retained when the resume position is reset or replayed. */
val Episode.playedPercentage: Int?
    get() = if (duration > 0) (maxOf(playedPosition, position).toLong() * 100 / duration).coerceIn(0, 100).toInt() else null

fun Episode.isPlaybackFinished(threshold: Int): Boolean =
    playState == EpisodeState.PLAYED.code || completionReached(position, duration, threshold)

/** Called in a Realm write with a position captured from the player, never an old flow snapshot. */
fun Episode.recordPlaybackProgress(playerPosition: Int, threshold: Int, ended: Boolean = false, now: Long = nowInMillis()) {
    if (playerPosition < 0 && !ended) return
    val savedPosition = if (ended) maxOf(duration, playerPosition, 0) else playerPosition
    position = savedPosition
    if ((duration > 0 || ended) && savedPosition > duration) duration = savedPosition
    playedPosition = maxOf(playedPosition, savedPosition)
    if (startPosition >= 0 && savedPosition > startPosition) {
        playedDuration = (playedDurationWhenStarted.toLong() + savedPosition - startPosition).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    if (startTime > 0) {
        var delta = (now - startTime).coerceAtLeast(0)
        if (delta > 3L * maxOf(playedDuration, 60_000)) {
            startTime = now
            delta = 0
        }
        timeSpent = timeSpentOnStart + delta
    }
    lastPlayedTime = now
    val finished = ended || completionReached(savedPosition, duration, threshold)
    if (finished) {
        if (playbackCompletionTime == 0L || ended) playbackCompletionTime = now
        val keepState = playState == EpisodeState.FOREVER.code ||
            (playState == EpisodeState.AGAIN.code && now - playStateSetTime < duration)
        if (!keepState && playState != EpisodeState.PLAYED.code) setPlayState(EpisodeState.PLAYED, setTime = now)
        // Marking played normally resets position. Keep playback/resume intact until it actually ends.
        position = if (ended) 0 else savedPosition
    } else if (playState == EpisodeState.NEW.code) setPlayState(EpisodeState.PROGRESS, setTime = now)
}
