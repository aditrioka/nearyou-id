package id.nearyou.app.post

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/**
 * Pins the [SinglePostResponse] wire to the shipped timeline post DTO's mixed-case convention
 * (`city_name` / `liked_by_viewer` / `reply_count` snake_case; the rest bare camelCase) and the
 * PR #128 casing-drift negative guard. No DB — pure serializer behavior.
 */
class SinglePostResponseSerializationTest : StringSpec({

    // Mirror production ContentNegotiation.
    val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    "6.1 serializes with the mixed-case keys (snake city_name/liked_by_viewer/reply_count; camel rest)" {
        val encoded =
            json.encodeToString(
                SinglePostResponse.serializer(),
                SinglePostResponse(
                    id = "11111111-1111-1111-1111-111111111111",
                    authorUsername = "raka.jkt",
                    authorDisplayName = "Raka Pratama",
                    content = "halo",
                    cityName = "Jakarta Selatan",
                    createdAt = "2026-01-01T00:00:00Z",
                    likedByViewer = true,
                    replyCount = 5,
                ),
            )
        // snake_case keys
        encoded shouldContain "\"city_name\""
        encoded shouldContain "\"liked_by_viewer\""
        encoded shouldContain "\"reply_count\""
        // bare camelCase keys
        encoded shouldContain "\"id\""
        encoded shouldContain "\"authorUsername\""
        encoded shouldContain "\"authorDisplayName\""
        encoded shouldContain "\"content\""
        encoded shouldContain "\"createdAt\""
        // the snake-mapped fields MUST NOT also appear in camelCase
        encoded shouldNotContain "\"cityName\""
        encoded shouldNotContain "\"likedByViewer\""
        encoded shouldNotContain "\"replyCount\""
        // distanceM is null in v1 → omitted under explicitNulls = false
        encoded shouldNotContain "distanceM"
    }

    "6.2 negative guard — camelCase cityName does NOT bind (only @SerialName(\"city_name\") binds)" {
        // A decoy camelCase `cityName` alongside the real snake_case `city_name`: the camelCase key
        // is an unknown field (ignored), so `cityName` binds ONLY from `city_name`.
        val body =
            """{"id":"p1","authorUsername":"u","authorDisplayName":"D","content":"c",""" +
                """"cityName":"WRONG_CAMEL","city_name":"Jakarta","createdAt":"t",""" +
                """"liked_by_viewer":false,"reply_count":0}"""
        val dto = json.decodeFromString(SinglePostResponse.serializer(), body)
        dto.cityName shouldBe "Jakarta"
    }
})
