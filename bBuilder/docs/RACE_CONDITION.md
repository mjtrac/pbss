<!--
  Copyright (C) 2026 Mitch Trachtenberg
  Election Ballot System — licensed under the GNU General Public License v3.
  See <https://www.gnu.org/licenses/> for the full license text.
-->

# Parallel Scanning Race Condition

## Background

bCounter scans ballot images in parallel using a configurable thread pool
(default: half the available CPU cores).  Each worker thread independently
scans one image at a time, performing corner detection, homography, and mark
analysis.  Results are deposited into a `ConcurrentLinkedQueue` and drained
by a single writer thread that serializes all database writes.

## The Race Condition

When two copies of the same ballot (`c01` and `c02`) happen to finish scanning
at almost exactly the same time, the following sequence can occur:

```
Worker A (c01):  scan complete → deposit to queue
Worker B (c02):  scan complete → deposit to queue
Writer:          dequeue c01 → INSERT ballot_image WHERE image_path='...c01...' → committed
Writer:          dequeue c02 → INSERT ballot_image WHERE image_path='...c02...' → committed
```

This is the normal case and works correctly.  The race occurs when the writer
processes both results before the first transaction fully commits:

```
Worker A (c01):  scan complete → deposit to queue
Worker B (c02):  scan complete → deposit to queue
Writer:          dequeue c01 → INSERT ... (transaction not yet visible)
Writer:          dequeue c02 → INSERT ... → constraint violation (c01 path conflicts)
                               ← silently dropped in original code
```

In practice this is rare — observed once in 2880 images — but when it occurs
it causes one ballot's votes to be missing from the results, producing a
delta of -1 for every candidate on that ballot.

## Why the Single Writer Does Not Fully Prevent It

The writer thread serializes the DB writes but SQLite's WAL (write-ahead log)
mode means a transaction committed by the writer may not be immediately
readable by a subsequent query within the same connection pool, especially
under connection pool sizes greater than 1.  The connection pool is now set
to `maximum-pool-size=1` which greatly reduces the window, but does not
eliminate it entirely under very fast back-to-back writes.

## Mitigations

Three layers of protection are in place:

### 1. Connection Pool Size = 1

`application.properties` sets:

```properties
spring.datasource.hikari.maximum-pool-size=1
spring.datasource.hikari.minimum-idle=1
```

A single SQLite connection prevents concurrent write transactions entirely.
This is the primary protection and eliminates the race under normal conditions.

### 2. Atomic Insert with Constraint Logging

`VoteRecordService.persist()` performs a single `INSERT` attempt rather than
a check-then-insert pattern.  If a `UNIQUE` constraint violation fires on
`image_path`, the exception is caught, the duplicate is confirmed by a
follow-up lookup, and the event is logged at WARN level:

```
Duplicate ballot skipped (parallel race) — already in database: /path/to/ballot.png
```

The method returns a `PersistStatus` enum value (`SAVED`, `RACE_SKIP`,
`TRUE_DUPLICATE`, or `ERROR`) rather than void, allowing the caller to act on
the outcome.

### 3. End-of-Scan Retry Queue

`ScanController` maintains a `retryList`.  When `persist()` returns
`RACE_SKIP`, the result is added to this list rather than discarded.  After
the main scan loop completes, the writer pauses briefly (200 ms) to allow any
in-flight transactions to commit, then retries each entry:

```
Retrying 1 race-skipped ballot(s)
Retry succeeded: ballot_1_2_1_1_1_2__messy_marks__clean__c02.png
```

At retry time the race winner's transaction is guaranteed to have committed,
so the retry either succeeds (the ballot is saved and its votes recorded) or
correctly identifies the entry as a true duplicate from a prior scan session.

## Observability

The following log messages are relevant:

| Level | Message | Meaning |
|---|---|---|
| WARN | `Duplicate ballot skipped (parallel race) — already in database: …` | Race detected; ballot queued for retry |
| INFO | `Retrying N race-skipped ballot(s)` | Retry phase starting |
| INFO | `Retry succeeded: ballot_name.png` | Ballot saved on retry |
| WARN | `Retry confirmed duplicate (race winner already saved): ballot_name.png` | True duplicate; no action needed |
| ERROR | `Retry failed for ballot_name.png: …` | Unexpected error during retry |

If the verify step still shows a delta=-1 mismatch after a scan that logged
no retry activity, the missing ballot was dropped silently before the
constraint-logging fix was in place, or was lost to an unrelated error.
In that case, reset and rescan to obtain a complete result.

## Production Guidance

- Keep `maximum-pool-size=1` in `application.properties` for SQLite.
- Monitor the log for `parallel race` warnings after each scan.
- If deploying against PostgreSQL (which supports true concurrent writers),
  replace the retry-queue approach with a database-level `INSERT … ON CONFLICT
  DO NOTHING` or an equivalent upsert, and increase the connection pool size
  accordingly.
