# Coordinated collaboration integration candidate

This branch combines the current heads of the attachment, My Work, presence
recovery, virtual-field and capacity PRs. It is a qualification candidate, not
a published release or a deployed host.

| PR | Included source |
| --- | --- |
| #45 attachment settlement | `dc65a826ce362416d55d91136fa39225f4a7cf94` |
| #46 bounded My Work | `cdcf7ee36509c7a1fc6f22fa864209eb80a6ff34` |
| #47 presence recovery | `776ebe3fcb6492881bc9d7ec3f6ca6adfa320200` |
| #48 contributed field values | `1da538cf43ba1ed80e3f8fd599729b5691563cbf` |
| #49 capacity qualification | `56a8b760d9c1a1862cb11e80fad07cf5cdc20b3a` |

All five heads are ancestors of integration commit
`44a89d77b1ad3a39d26c512ada78af0884872013`. The stacked My Work and capacity
branches did not contain the latest heads of their base PRs, so those base
heads were merged explicitly as well. No production source was rewritten during
integration. The core prerequisite remains
`4.1.0-virtual-field-values-1196-cc3981bb3-SNAPSHOT`; Redis remains the existing
published `0.1.0-redis-7fe5492-SNAPSHOT` until its coordinated update is published
and independently verified.

The caller and manifest both select released `kingsrook/qqq-orb@0.8`. The
canonical validator extracted from released 0.8.32 and CircleCI schema validation
pass; no unpublished orb changes are used.

Local `./mvnw.cmd verify -Dqqq.mywork.load=true` ran 238 tests with no skips, but
reported three failures and four errors. Failures include Redis command read
timeouts/rejoin assertions and PostgreSQL connection I/O during the attachment
fixture. Docker Desktop's published-port path has independently exhibited
300–900 ms replies after idle, compared with 0–58 ms on a direct Docker network.
This observation is not proof of every failure's cause. No timeout, assertion,
security boundary, or quality gate was weakened. Native hosted Linux results
are required before publication; the local run is **not** a passing gate.

The successful earlier combined-source checks remain historical evidence only.
Host TLS/ACL, actual identity/proxy/provider acceptance, maintenance/restore
enforcement, artifact publication, and deployment are separate gates.
