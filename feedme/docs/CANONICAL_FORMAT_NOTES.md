# Canonical format assertions

The public `CanonicalFormats` object implements the canonical bundle's `uri`, `uuid`,
and `date-time` formats in common Kotlin for Android/JVM, iOS, and the server's
Networknt custom-format adapters. `supports` identifies these exact,
case-sensitive names. Calling `accepts` with any other name throws a fixed
configuration error without exposing the name or instance. The schema compiler
must reject unsupported format declarations before instance evaluation.

The [JSON Schema 2020-12 validation specification](https://json-schema.org/draft/2020-12/json-schema-validation#section-7.3)
defines these string formats. The enclosing validator applies a format assertion
only to string instances and enables assertions explicitly. This helper does not
claim implementation of every format in the general Format-Assertion vocabulary.

## URI

The implementation scans the complete `URI` production from
[RFC 3986, Appendix A](https://www.rfc-editor.org/rfc/rfc3986.html#appendix-A),
including a required scheme, every hierarchical path alternative, optional query,
and optional fragment. Relative references and raw Unicode are rejected. All
component alphabets and percent triplets are checked without decoding or rewriting
them. Encoded bytes need not form UTF-8; a URI is not an IRI.

Authority parsing supports userinfo, registered names, IPv6, IPvFuture and numeric
ports. IPv6 supports one compression marker and a final IPv4 component with strict
decimal octets. Zone identifiers are outside the pinned RFC 3986 grammar.
Registered names can contain numeric dotted tokens that do not satisfy IPv4;
the grammar falls back to `reg-name`. Empty authority, host, userinfo and port
are legal where the grammar permits them, and port digits have no numeric maximum.

These are generic URI assertions. They do not establish HTTP suitability, DNS
existence, transport reachability, permitted schemes, credential safety or fetch
authorization. Such checks belong to the operation that uses the identifier.

## UUID

The implementation checks the 36-character, 8-4-4-4-12 hexadecimal representation
in [RFC 4122, section 3](https://www.rfc-editor.org/rfc/rfc4122.html#section-3).
Uppercase, lowercase, mixed case and the nil value are supported. The text grammar
does not restrict version or variant nibbles. A URN prefix, braces, whitespace,
non-ASCII digits, or missing/misplaced hyphens are rejected. The pinned JSON Schema
dialect references RFC 4122, despite its later replacement by RFC 9562.

## Date-time

The implementation checks the `date-time` grammar and calendar restrictions of
[RFC 3339, sections 5.6–5.7](https://www.rfc-editor.org/rfc/rfc3339.html#section-5.6).
It supports four ASCII year digits (including year 0000), Gregorian leap years,
case-insensitive `T`/`Z`, arbitrarily long nonempty fractional seconds, and the
required UTC or signed hour/minute offset. Offsets through 23:59 and `-00:00` are
accepted. Hours of 24, implicit timezones, truncated seconds, alternate ISO 8601
dates, space separators and trailing text are rejected.

A second of 60 must be at UTC 23:59 on an actual leap-second date after applying
the offset, including date and year rollover. The pinned 27-date table derives from
the official [IERS leap-second history](https://hpiers.obspm.fr/iers/bul/bulc/Leap_Second.dat),
reviewed through [Bulletin C 72, issued 6 July 2026](https://datacenter.iers.org/data/16/bulletinc-072.txt).
Its latest event is 31 December 2016; the bulletin announces no insertion in
December 2026. The source history expires 28 June 2027 and is retained in the
golden corpus. `2016-06-30T23:59:60Z` is rejected: month-end alone is insufficient.

Unannounced future `:60` values fail validation. A newly announced event requires
a reviewed table and test update before it is accepted; runtime validation never
fetches announcements. No negative leap seconds have been announced. If one is
announced, update the event model to reject its omitted `:59` as well. The source
expiry does not invalidate ordinary timestamps or the already established history.

## Verification and execution bounds

The common tests cover both accepting and rejecting cases, component delimiters,
IPv6 compression at every position, IPv4 tails, Unicode, calendar boundaries,
offset-adjusted leap seconds, redacted configuration failures, and million-character
paths/fractions. The golden corpus at
`docs/verification/canonical-validation/format-corpus.json` preserves all 119
date-time, URI and UUID cases from the primary
[JSON Schema format tests at commit f6fd52a0a95472e079cbfc6ef7f089702b80e045](https://github.com/json-schema-org/JSON-Schema-Test-Suite/tree/f6fd52a0a95472e079cbfc6ef7f089702b80e045/tests/draft2020-12/optional/format),
their MIT license, and additional RFC/IERS regressions. The upstream suite does not
test nonexistent month-end leap seconds; its positive leap cases are actual events.
Preservation and inspection of a corpus do not by themselves claim its execution.

Scanners are linear in input length and use bounded auxiliary state. They perform
no recursion, unbounded regex matching, network lookup, date-library conversion,
or numeric parsing of fractions/ports. The enclosing wire parser enforces its
document resource policy before validation. Platform-specific URL/date libraries
are intentionally absent, so JVM and Native use the same algorithm.

## Networknt 3.0.7 findings and adaptation

The raw library's date-time validator rejects valid unknown-local-offset notation,
fractions beyond nine digits, some offset-shifted leap seconds, and large valid
numeric offsets. It accepts a space separator and trailing content. Its URI
validator accepts alphabetic ports and repeated `@`, and rejects IPvFuture. These
are observed library differences, not reasons to change the RFC expectations.
The shared implementation's earlier hypothetical month-end leap policy was also
too permissive and was corrected using the IERS data above.

The official tagged [Format API](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/format/Format.java)
supports a string adapter overriding `getName(): String` and
`matches(ExecutionContext, String): Boolean`; its default node overload ignores
non-string instances. The tagged [Dialect builder](https://github.com/networknt/json-schema-validator/blob/3.0.7/src/main/java/com/networknt/schema/dialect/Dialect.java)
copies the standard dialect and replaces an existing format by name through
`Dialect.builder(Dialects.getDraft202012()).format(adapter).build()`.
Keep explicit format assertions enabled when constructing the registry.

With these adapters the server and common validators share format logic. Their
agreement is therefore not independent proof of format correctness; the pinned
official cases and RFC/IERS regressions provide that separate expectation source.
Networknt remains independent for structural and numeric schema evaluation.
