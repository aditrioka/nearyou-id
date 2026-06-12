package id.nearyou.app.admin.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [InetSanitizer] — the guard that keeps a non-literal
 * `clientIp` ("unknown" / "localhost") from throwing at the `?::inet` cast
 * in the admin_sessions / admin_actions_log INSERTs (which would 500 the
 * login + break no-enumeration on a failure path).
 */
class InetSanitizerTest : StringSpec({

    "accepts valid IPv4 literals" {
        InetSanitizer.isInetLiteral("1.2.3.4") shouldBe true
        InetSanitizer.isInetLiteral("255.255.255.255") shouldBe true
        InetSanitizer.isInetLiteral("0.0.0.0") shouldBe true
    }

    "accepts valid IPv6 literals" {
        InetSanitizer.isInetLiteral("::1") shouldBe true
        InetSanitizer.isInetLiteral("fe80::1") shouldBe true
        InetSanitizer.isInetLiteral("2001:db8::ff00:42:8329") shouldBe true
    }

    "rejects hostnames and the unknown sentinel string" {
        InetSanitizer.isInetLiteral("localhost") shouldBe false
        InetSanitizer.isInetLiteral("unknown") shouldBe false
        InetSanitizer.isInetLiteral("not-an-ip-literal") shouldBe false
        InetSanitizer.isInetLiteral("") shouldBe false
    }

    "rejects out-of-range IPv4 octets" {
        InetSanitizer.isInetLiteral("999.1.1.1") shouldBe false
        InetSanitizer.isInetLiteral("1.2.3.256") shouldBe false
    }

    "rejects structurally invalid colon-junk that passes the hex-colon charset" {
        // Each of these matched the old `^[0-9a-fA-F:]+$` screen but throws at
        // Postgres' `?::inet` cast — a 500 on the login/audit path (the exact
        // failure mode this class exists to prevent).
        InetSanitizer.isInetLiteral(":::") shouldBe false
        InetSanitizer.isInetLiteral(":") shouldBe false
        InetSanitizer.isInetLiteral("12345::") shouldBe false
        InetSanitizer.isInetLiteral("1:2:3:4:5:6:7:8:9") shouldBe false
        InetSanitizer.isInetLiteral("fe80::1::2") shouldBe false
        InetSanitizer.isInetLiteral("f".repeat(46)) shouldBe false
    }

    "orFallback returns the IP when literal, the fallback otherwise" {
        InetSanitizer.orFallback("1.2.3.4", "0.0.0.0") shouldBe "1.2.3.4"
        InetSanitizer.orFallback("localhost", "0.0.0.0") shouldBe "0.0.0.0"
        InetSanitizer.orFallback("unknown", null) shouldBe null
        InetSanitizer.orFallback("9.9.9.9", null) shouldBe "9.9.9.9"
    }
})
