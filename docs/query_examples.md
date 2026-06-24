# bSuite Database Query Examples

The ballot counter writes all scan results to a SQLite database (`counter_results.db`
in the `bSuite/bCounter/` folder). You can query it directly with any SQL tool.

---

## Recommended Tool: DB Browser for SQLite

Download free from: **https://sqlitebrowser.org/dl/**

Available for macOS, Windows, and Linux. Open `counter_results.db` directly, browse
tables, and paste the queries below into the "Execute SQL" tab.

For PostgreSQL, use **pgAdmin** (https://www.pgadmin.org/) or `psql` on the command line.

---

## Schema Overview

```
barcode          — one row per unique barcode; holds jurisdiction, region, party, etc.
ballot_image     — one row per scanned image; links to barcode
contest          — one row per unique contest title + type
candidate        — one row per candidate within a contest
vote_opportunity — one row per indicator box per image (the core fact table)
                   vote_status: VOTED | OVERVOTED | UNMARKED
```

Key joins:
```
vote_opportunity → candidate    (via candidate_id_fk)
vote_opportunity → contest      (via contest_id)
vote_opportunity → ballot_image (via ballot_image_id)
ballot_image     → barcode      (via barcode_id)
```

---

## 1. Total votes per candidate across all precincts

```sql
SELECT
    k.contest_title                                          AS contest,
    c.candidate_name                                         AS candidate,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')         AS votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED')     AS overvotes,
    COUNT(*) FILTER (WHERE v.vote_status = 'UNMARKED')      AS unmarked
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
GROUP BY k.contest_title, c.candidate_name
ORDER BY k.contest_title, votes DESC;
```

> `FILTER (WHERE ...)` works identically in PostgreSQL 9.4+ and SQLite 3.23+.

---

## 2. Votes per candidate per precinct

The `region_id` column in the `barcode` table holds the precinct identifier
as encoded in the ballot barcode.

```sql
SELECT
    k.contest_title                                          AS contest,
    b.region_id                                              AS precinct,
    c.candidate_name                                         AS candidate,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')         AS votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED')     AS overvotes
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
JOIN ballot_image      i ON v.ballot_image_id = i.id
JOIN barcode           b ON i.barcode_id      = b.id
GROUP BY k.contest_title, b.region_id, c.candidate_name
ORDER BY k.contest_title, b.region_id, votes DESC;
```

---

## 3. Votes per candidate per party

```sql
SELECT
    k.contest_title                                          AS contest,
    b.party_id                                               AS party,
    c.candidate_name                                         AS candidate,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')         AS votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED')     AS overvotes
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
JOIN ballot_image      i ON v.ballot_image_id = i.id
JOIN barcode           b ON i.barcode_id      = b.id
GROUP BY k.contest_title, b.party_id, c.candidate_name
ORDER BY k.contest_title, b.party_id, votes DESC;
```

---

## 4. Votes per candidate per precinct and party

```sql
SELECT
    k.contest_title                                          AS contest,
    b.region_id                                              AS precinct,
    b.party_id                                               AS party,
    c.candidate_name                                         AS candidate,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')         AS votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED')     AS overvotes
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
JOIN ballot_image      i ON v.ballot_image_id = i.id
JOIN barcode           b ON i.barcode_id      = b.id
GROUP BY k.contest_title, b.region_id, b.party_id, c.candidate_name
ORDER BY k.contest_title, b.region_id, b.party_id, votes DESC;
```

---

## 5. Total ballots scanned per precinct

```sql
SELECT
    b.region_id             AS precinct,
    COUNT(DISTINCT i.id)    AS ballots_scanned
FROM ballot_image  i
JOIN barcode       b ON i.barcode_id = b.id
GROUP BY b.region_id
ORDER BY b.region_id;
```

---

## 6. Overvoted ballots — which images and which contests

```sql
SELECT
    i.image_name                                             AS image,
    i.image_path                                             AS path,
    k.contest_title                                          AS contest,
    COUNT(*)                                                 AS overvoted_indicators
FROM vote_opportunity  v
JOIN ballot_image      i ON v.ballot_image_id = i.id
JOIN contest           k ON v.contest_id      = k.id
WHERE v.vote_status = 'OVERVOTED'
GROUP BY i.image_name, i.image_path, k.contest_title
ORDER BY i.image_name, k.contest_title;
```

---

## 7. Write-in votes by contest and precinct

```sql
SELECT
    k.contest_title         AS contest,
    b.region_id             AS precinct,
    COUNT(*)                AS write_in_votes
FROM vote_opportunity  v
JOIN candidate         c ON v.candidate_id_fk = c.id
JOIN contest           k ON v.contest_id      = k.id
JOIN ballot_image      i ON v.ballot_image_id = i.id
JOIN barcode           b ON i.barcode_id      = b.id
WHERE c.write_in = 1
  AND v.vote_status = 'VOTED'
GROUP BY k.contest_title, b.region_id
ORDER BY k.contest_title, b.region_id;
```

> **PostgreSQL:** use `c.write_in = TRUE` instead of `c.write_in = 1`.

---

## 8. Summary dashboard — one row per contest

```sql
SELECT
    k.contest_title                                          AS contest,
    k.contest_type                                           AS type,
    COUNT(DISTINCT i.id)                                     AS ballots,
    COUNT(*) FILTER (WHERE v.vote_status = 'VOTED')         AS total_votes,
    COUNT(*) FILTER (WHERE v.vote_status = 'OVERVOTED')     AS overvotes,
    COUNT(*) FILTER (WHERE v.vote_status = 'UNMARKED')      AS unmarked
FROM vote_opportunity  v
JOIN contest           k ON v.contest_id      = k.id
JOIN ballot_image      i ON v.ballot_image_id = i.id
GROUP BY k.contest_title, k.contest_type
ORDER BY k.contest_title;
```

---

## SQLite vs PostgreSQL compatibility notes

| Feature | SQLite | PostgreSQL |
|---|---|---|
| `FILTER (WHERE ...)` | supported (v3.23+, 2018) | supported (v9.4+) |
| Boolean columns | stored as `0` / `1` | use `TRUE` / `FALSE` |
| `COUNT(DISTINCT ...)` | supported | supported |
| String quoting | single quotes | single quotes |

DB Browser for SQLite ships with SQLite 3.45+ so all queries above run as written.
For older SQLite (pre-3.23), replace `FILTER` clauses with `CASE` expressions:

```sql
-- Equivalent to COUNT(*) FILTER (WHERE vote_status = 'VOTED')
SUM(CASE WHEN v.vote_status = 'VOTED' THEN 1 ELSE 0 END)
```

---

## Running queries from the command line

**SQLite:**
```bash
# Interactive shell
sqlite3 bSuite/bCounter/counter_results.db

# Run a file of SQL
sqlite3 bSuite/bCounter/counter_results.db < query.sql

# One-liner
sqlite3 -column -header bSuite/bCounter/counter_results.db \
  "SELECT k.contest_title, c.candidate_name, COUNT(*) votes
   FROM vote_opportunity v
   JOIN candidate c ON v.candidate_id_fk=c.id
   JOIN contest k ON v.contest_id=k.id
   WHERE v.vote_status='VOTED'
   GROUP BY k.contest_title, c.candidate_name
   ORDER BY k.contest_title, votes DESC;"
```

**PostgreSQL:**
```bash
psql -U postgres -d election_counter
psql -U postgres -d election_counter -f query.sql
```
