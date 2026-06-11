package id.nearyou.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/*
 * The deterministic letter-avatar contract for the shared post card (`mobile-post-card` § "Letter
 * avatar derivation is deterministic"): initials from the display name + a stable tonal-container
 * pick from the username. Both derivations are pure commonMain functions (unit-tested in
 * commonTest without composing UI); LetterAvatar is the composable shell that renders them from
 * `NearYouTheme` tokens only.
 */

/**
 * Initials = first Unicode code point of the first whitespace-separated word + first code point of
 * the last word (single-word name → one code point), uppercased. Empty segments are discarded, so
 * leading/trailing/consecutive whitespace is safe; a blank or whitespace-only name yields "" (the
 * avatar then renders its container with no glyph — `users.display_name` is NOT NULL but carries
 * no non-empty CHECK, and `PostDetailRoute` defaults the field to ""). Code-point-based so a
 * surrogate-pair-leading word (e.g. an emoji) is never split in half.
 */
fun avatarInitials(displayName: String): String {
    val words = displayName.split(' ', '\t', '\n', '\r').filter { it.isNotBlank() }
    if (words.isEmpty()) return ""
    val first = firstCodePoint(words.first())
    return if (words.size == 1) {
        first.uppercase()
    } else {
        (first + firstCodePoint(words.last())).uppercase()
    }
}

/** The full first code point of [word] as a String — two chars when a surrogate pair leads. */
private fun firstCodePoint(word: String): String {
    val first = word[0]
    return if (first.isHighSurrogate() && word.length > 1 && word[1].isLowSurrogate()) {
        word.substring(0, 2)
    } else {
        first.toString()
    }
}

/**
 * The Material 3 tonal-container pairs an avatar may use. Exactly the three scheme container slots
 * — the largest palette expressible from `NearYouTheme` tokens without inventing colors (design D4;
 * the mockup's wider hue variety is a CSS approximation, theme tokens take precedence).
 */
enum class AvatarTone { Primary, Secondary, Tertiary }

/**
 * Deterministic username → tone mapping, stable across recompositions, feeds, and sessions.
 * An explicit fold (not `hashCode()`) so the result is platform-pinned by construction.
 */
fun avatarTone(username: String): AvatarTone {
    val h = username.fold(0) { acc, c -> acc * 31 + c.code }
    return AvatarTone.entries[((h % 3) + 3) % 3]
}

/**
 * The circular letter avatar: [avatarInitials] of [displayName] centered on the [avatarTone]
 * container of [username]. Non-interactive (identity is not a tap target — no profile screen
 * exists yet, issue #196); the whole card owns the single tap.
 */
@Composable
fun LetterAvatar(
    displayName: String,
    username: String,
    modifier: Modifier = Modifier,
) {
    val initials = remember(displayName) { avatarInitials(displayName) }
    val tone = remember(username) { avatarTone(username) }
    val container =
        when (tone) {
            AvatarTone.Primary -> MaterialTheme.colorScheme.primaryContainer
            AvatarTone.Secondary -> MaterialTheme.colorScheme.secondaryContainer
            AvatarTone.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
        }
    val content =
        when (tone) {
            AvatarTone.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
            AvatarTone.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
            AvatarTone.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
        }
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNotEmpty()) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleSmall,
                color = content,
            )
        }
    }
}
