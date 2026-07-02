package id.nearyou.app.admin.postedits

import id.nearyou.app.admin.routes.toViewMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * Fast, non-DB unit tests for the Post Edit History viewer
 * (`admin-post-edit-history`):
 *  - the opaque [PostEditCursor] round-trip + malformed-token fallback
 *    (tasks.md 4.5d), and
 *  - the type-boundary geography guard (tasks.md 4.8) + the author-display
 *    degradation for a (referentially-impossible but defensively-handled) null
 *    username (tasks.md 4.7), asserted directly on the view-model types and the
 *    route's view-map shaping — not only via rendered HTML.
 */
class PostEditHistoryUnitTest : StringSpec({

    "cursor round-trips through encode/decode" {
        val cursor = PostEditCursor(Instant.parse("2026-06-10T20:12:34.567890Z"))
        val decoded = PostEditCursor.decode(cursor.encode())
        decoded shouldBe cursor
    }

    "malformed / blank / null cursor tokens decode to null (→ first page)" {
        PostEditCursor.decode("not-a-valid-cursor").shouldBeNull()
        PostEditCursor.decode("").shouldBeNull()
        PostEditCursor.decode(null).shouldBeNull()
        // valid base64url but not a parseable Long
        PostEditCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("abc".toByteArray())).shouldBeNull()
    }

    // The view-model types that leave the service MUST carry no GEOGRAPHY /
    // latitude / longitude / coordinate field (by name OR by type), so
    // coordinates cannot leak via the template, hx-vals, a debug attribute, or a
    // serialized JSON island (spec: "the view model carries no geography or
    // coordinate field"). The ONLY location signal is the boolean locationChanged.
    val forbiddenName =
        Regex(
            "(coordinate|latitude|longitude|geography|geometry|location_snapshot|actual_location|" +
                "\\blat\\b|\\blon\\b|\\blng\\b|\\bwkb\\b|\\bwkt\\b|\\bpoint\\b)",
            RegexOption.IGNORE_CASE,
        )
    val forbiddenType = Regex("(geography|geometry|point)", RegexOption.IGNORE_CASE)

    "PostVersionView exposes only a boolean location signal, no coordinate field" {
        val props = PostVersionView::class.memberProperties
        props.map { it.name }.filter { forbiddenName.containsMatchIn(it) } shouldBe emptyList()
        props.map { it.returnType.toString() }.filter { forbiddenType.containsMatchIn(it) } shouldBe emptyList()
        // The location signal IS present, and it is exactly a Boolean.
        val loc = props.first { it.name == "locationChanged" }
        loc.returnType.toString() shouldBe "kotlin.Boolean"
    }

    "PostEditSnapshot + LivePostVersion expose no coordinate field" {
        listOf(PostEditSnapshot::class, LivePostVersion::class).forEach { klass ->
            val props = klass.memberProperties
            props.map { it.name }.filter { forbiddenName.containsMatchIn(it) } shouldBe emptyList()
            props.map { it.returnType.toString() }.filter { forbiddenType.containsMatchIn(it) } shouldBe emptyList()
        }
        // The snapshot still carries the boolean signal; the live version has no
        // location field at all (it is the newest version — nothing newer to
        // compare against).
        PostEditSnapshot::class.memberProperties.first { it.name == "locationChanged" }
            .returnType.toString() shouldBe "kotlin.Boolean"
        LivePostVersion::class.memberProperties.none { it.name == "locationChanged" } shouldBe true
    }

    "author cell degrades to the user id when the username is absent, keeping the deep-link" {
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val view =
            PostVersionView(
                versionLabel = "Versi ke-2",
                isLive = false,
                content = "snapshot text",
                timestamp = Instant.parse("2026-06-10T20:12:00Z"),
                authorId = id,
                authorUsername = null,
                locationChanged = false,
            )
        val map = view.toViewMap()
        map["authorDisplay"] shouldBe id.toString()
        map["authorUrl"] shouldBe "/admin/users?q=$id"
        // The shaped map carries no geography/coordinate key either.
        map.keys.filter { forbiddenName.containsMatchIn(it) } shouldContainExactly listOf()
    }

    "view-map escapes nothing itself (Pebble autoescapes) but carries the raw content + boolean flag" {
        val view =
            PostVersionView(
                versionLabel = "Versi terbaru",
                isLive = true,
                content = "<b>hi</b>",
                timestamp = Instant.parse("2026-06-10T21:40:00Z"),
                authorId = UUID.randomUUID(),
                authorUsername = "rina.sore",
                locationChanged = false,
            )
        val map = view.toViewMap()
        map["content"] shouldBe "<b>hi</b>" // raw in the map; Pebble escapes on render
        map["locationChanged"] shouldBe false
        map.keys.toString() shouldNotContain "location_snapshot"
    }
})
