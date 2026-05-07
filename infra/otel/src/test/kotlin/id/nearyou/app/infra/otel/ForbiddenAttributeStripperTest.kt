package id.nearyou.app.infra.otel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Regression tests for [ForbiddenAttributeStripper.FORBIDDEN_KEYS] coverage.
 *
 * Locks both OLD and NEW OTel semantic-convention names for peer-IP / client
 * info — caught at staging soak verification of the
 * `observability-otel-foundation` change: the deployed
 * `opentelemetry-ktor-3.0:2.25.0-alpha` instrumentation emitted
 * `network.peer.address` (new semconv), but the original `FORBIDDEN_KEYS`
 * list only covered old names (`net.peer.ip`), letting peer-IP values reach
 * Tempo. See PR #66 staging soak findings.
 */
class ForbiddenAttributeStripperTest : StringSpec({
    "FORBIDDEN_KEYS covers OLD-semconv peer-IP names" {
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "net.peer.ip"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "net.peer.port"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "net.sock.peer.addr"
    }

    "FORBIDDEN_KEYS covers NEW-semconv peer-IP names (OTel Java 2.x)" {
        // Verified in production at staging soak: opentelemetry-ktor-3.0:2.25.0-alpha
        // emits `network.peer.address`, not the old `net.peer.ip`. Keep both
        // mapped so a BOM downgrade or alternate instrumentation source does
        // not regress.
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "network.peer.address"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "network.peer.port"
    }

    "FORBIDDEN_KEYS covers HTTP-semconv client identity names" {
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "client.address"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "client.port"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "http.client_ip"
    }

    "FORBIDDEN_KEYS covers defensive raw user-UUID typo'd keys" {
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "user_id"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "user_uuid"
        ForbiddenAttributeStripper.FORBIDDEN_KEYS shouldContain "user.uuid"
    }

    "FORBIDDEN_KEYS does NOT include server's own bind info" {
        // server.address / server.port = the server's own bind address/port,
        // not the peer/client. Not a privacy concern; keep observable.
        (ForbiddenAttributeStripper.FORBIDDEN_KEYS.contains("server.address")) shouldBe false
        (ForbiddenAttributeStripper.FORBIDDEN_KEYS.contains("server.port")) shouldBe false
    }
})
