## MODIFIED Requirements

### Requirement: Structured WARN log on publish failure

The chat send handler SHALL log a structured WARN line on every publish failure path (Failure return + thrown exception caught after the chat-foundation tx commits). The line SHALL carry these fields exactly: `event = "chat_realtime_publish_failed"`, `conversation_id = <UUID>`, `message_id = <UUID>`, `error_class = <fully-qualified class name>`. Log level SHALL be WARN (not ERROR) per design § D9.

The publish call SHALL be wrapped by `:infra:otel`'s `withSpan("chat.realtime.publish", attributes)` helper. When the publish call fails (Failure return OR thrown exception caught post-commit), the resulting span SHALL be ended with `Span.setStatus(StatusCode.ERROR)` AND SHALL record a span event with attributes matching the WARN log line: `event = "chat_realtime_publish_failed"`, `error.type = <fully-qualified class name>`. Span attributes set on the wrapper span SHALL include `conversation_id` (UUID, hex string) AND `message_id` (UUID, hex string). The span MUST NOT carry the raw chat message content, the raw service role key value, or any other forbidden attribute per the `observability-otel-foundation` capability spec.

The handler-completion-required preservation rule continues to apply: when the JVM crashes between the chat-foundation commit and the publish call (or before the WARN log fires), neither the WARN log nor the span will be emitted. This is the documented crash-window contract per `design.md` § D1; an outbox / replay log is still NOT introduced.

The Supabase service role key SHALL NEVER appear in any log field. Two enforcement scenarios apply: (1) literal-source-grep — any source-code occurrence of the literal `supabase-service-role-key` SHALL be only at the `secretKey(env, "supabase-service-role-key")` call site; (2) defense-in-depth — the resolved key VALUE itself SHALL NEVER appear in any log field captured during the publish call path.

#### Scenario: WARN log emitted on PublishResult.Failure
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` returns `PublishResult.Failure("java.io.IOException")`
- **WHEN** the chat send handler completes
- **THEN** exactly one WARN log line is captured with fields `event = "chat_realtime_publish_failed"`, `conversation_id` matching the request, `message_id` matching the inserted row, `error_class = "java.io.IOException"`

#### Scenario: WARN log emitted on thrown exception caught post-commit
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` throws `java.net.SocketTimeoutException`
- **WHEN** the chat send handler catches the exception after the tx commits
- **THEN** a WARN log line is captured with `error_class = "java.net.SocketTimeoutException"` (the fully-qualified class name of the thrown exception)

#### Scenario: No WARN log on PublishResult.Success
- **WHEN** `publish` returns `PublishResult.Success`
- **THEN** no log line with `event = "chat_realtime_publish_failed"` is emitted for that request

#### Scenario: OTel span emitted on PublishResult.Failure pairs with WARN log
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` returns `PublishResult.Failure("java.io.IOException")` AND the OTel SpanRecorder test-double captures all emitted spans
- **WHEN** the chat send handler completes
- **THEN** a span named `"chat.realtime.publish"` is captured AND its status is `StatusCode.ERROR` AND its captured events include one with `event = "chat_realtime_publish_failed"` AND `error.type = "java.io.IOException"` AND its attributes include `conversation_id` and `message_id` matching the request

#### Scenario: OTel span emitted on thrown exception pairs with WARN log
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` throws `java.net.SocketTimeoutException`
- **WHEN** the chat send handler completes
- **THEN** a span named `"chat.realtime.publish"` is captured AND its status is `StatusCode.ERROR` AND `Span.recordException(...)` captured the thrown exception AND its events include one with `event = "chat_realtime_publish_failed"` AND `error.type = "java.net.SocketTimeoutException"`

#### Scenario: OTel span on PublishResult.Success has OK status, no failure event
- **GIVEN** the test-double `FakeChatRealtimeClient.publish` returns `PublishResult.Success`
- **WHEN** the chat send handler completes
- **THEN** the captured `"chat.realtime.publish"` span has status OK AND no event with `event = "chat_realtime_publish_failed"` is recorded

#### Scenario: OTel span carries no raw chat content
- **GIVEN** a chat send whose body content is the well-known sentinel string `"sentinel-chat-content-DO-NOT-LEAK"`
- **WHEN** the captured `"chat.realtime.publish"` span's attributes and events are scanned for the literal sentinel
- **THEN** the literal does NOT appear in any attribute value, event attribute, or span name (the forbidden-attributes contract from `observability-otel-foundation` applies)

#### Scenario: Service role key slot name appears only at secretKey call site
- **WHEN** searching the backend source for the literal string `"supabase-service-role-key"`
- **THEN** the only occurrences are calls to `secretKey(env, "supabase-service-role-key")`

#### Scenario: Service role key VALUE never appears in logs
- **GIVEN** the resolved service role key value `K` (loaded at startup via `secretKey(env, ...)`) AND a test that captures all log lines emitted during a `publish` invocation (across both success and failure paths)
- **WHEN** the captured log messages are scanned for `K` (substring match)
- **THEN** `K` does NOT appear in any captured log line as a field value or message substring

#### Scenario: Service role key VALUE never appears in spans
- **GIVEN** the resolved service role key value `K` AND a test that captures all spans emitted during a `publish` invocation
- **WHEN** the captured spans' attributes and events are scanned for `K` (substring match)
- **THEN** `K` does NOT appear in any captured span attribute value or event attribute value
