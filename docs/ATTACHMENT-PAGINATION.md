# Standard attachment list pagination

The standard `GET /records/{table}/{pk}/attachments` endpoint now applies its
page offset and limit inside the native QQQ attachment query. It reads at most
`limit + 1` rows (maximum 101), projects the requested page, and uses the extra
authorized row only to determine whether to emit the next cursor. It performs no
count query and does not load the complete attachment list before slicing it.

The route, response schema, default limit 20, allowed limits 1–100, and existing
version-1 scoped offset cursor bytes are unchanged. Sorting is still descending
`createDate`, then descending `id`; equal timestamps therefore retain a stable tie
order. These are offset pages, not snapshots: concurrent insertion or deletion can
shift offsets. High offsets can require backend scanning; bounded returned rows
and host allocation are not a constant-time database performance guarantee.

Each request still touches the current person, resolves and authorizes the actual
source record, canonicalizes its primary key, excludes deleted-parent attachments,
and uses the existing per-entry edit/delete policy. The same entry projection is
evaluated for the lookahead row. There is no generic post-filter refill or inference
from a denied row. Sources transferred to retained archive records use the exact
current parent identity and its native authorization policy. This owner artifact
does not import Record Files or reconstruct its archive policy.

Metadata visibility is separate from content release. Listing retained or
quarantined metadata does not issue a download, preview or print capability; those
paths retain their lifecycle and current authorization checks. The cursor is a
routing/offset hint, not a signed authorization token. Current source access is
required even when the caller presents a previously valid cursor.

`AttachmentsService.listPage` is the bounded public service operation. The older
unpaged `list` method remains for compatibility; new page consumers should use
`listPage`. Cursor parsing/encoding is shared with the existing complete-list
pagination helper, so other Collaboration endpoints keep their previous behavior.

Verification: local focused run `target/attachment-pagination-focused.log` passed
40 tests on 2026-10-31 at 02:01:44, with zero failures/errors/skips and Checkstyle.
Two new tests execute the actual QQQ RDBMS action against H2 and observe native
offset/limit and returned-row bounds, equal timestamp ordering, deleted/other
source exclusion, retained flags, terminal pages and current revocation before
attachment SQL. The other 38 tests cover existing attachment service behavior and
the unchanged OpenAPI fixture. Root HTTP run47446 also passed all12 tests, including
the new regression covering three pages,
cursor scope, malformed/oversized input and a denied actor. Cross-qbit archive integration, full release verification and installation
of this new owner snapshot remain separate gates. No installed or frozen consumer
artifact has been replaced by this change.
