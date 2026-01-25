# Demo video script (about 3 minutes)

Record on one machine with the cluster up. Every number on screen comes from the README results or from the
run you do while recording; don't read numbers from this file aloud as if they were live.

Before recording:

```bash
docker compose up -d
docker compose exec -T postgres psql -U hotshield hotshield < loadtest/seed.sql
docker compose --profile cluster up -d --build
```

## 0:00 – 0:30 The problem

Show the README diagram. Say: a hot key sends the same read to Postgres thousands of times a second, and
when its cached copy is dropped, every concurrent reader misses at once. HotShield sits in front of
Postgres, caches popular keys, and collapses concurrent misses for one key into one query.

## 0:30 – 1:15 Coalescing

Open `SingleFlight` and the race test next to it. Say: the first caller for a key becomes the leader and
runs the query; the others wait on the same future. Point at the test: 1,000 callers block on a latch,
and the loader runs once.

Show the herd table in the README: after the PUT, cache-only mode sent 152 queries in a second and the
burst p99 was 1064.8 ms; with coalescing it was 63 queries and 55.8 ms.

## 1:15 – 2:00 Three nodes and invalidation

Run a few requests through nginx and show which node owns the key:

```bash
curl -s localhost:8080/items/42
curl -s localhost:8080/actuator/prometheus | grep forward
```

Say: rendezvous hashing gives each key one owner, so a miss on any node is forwarded to the owner instead
of going to the database. Then do a PUT and read the key again from each node to show the new version:

```bash
curl -s -X PUT localhost:8080/items/42 -H 'Content-Type: application/json' \
  -d '{"name":"item 42 demo","priceCents":4200,"stock":42}'
for p in 8081 8082 8083; do curl -s localhost:$p/items/42; echo; done
```

Say what can still go stale: an invalidation sent to a node that is paused is lost, so that node serves the
old row until its TTL. The staleness entry in `docs/EXPERIMENTS.md` measured this.

## 2:00 – 2:40 Measurements

Show the zipf table: 3000 req/s, the cache cut DB queries per request from 1.0000 to 0.2251. Say that
coalescing adds nothing on zipf because concurrent misses for one key are rare there; it matters on the herd.

Show the milestone 12 entry: the 4000 req/s failure came from the load generator (one connection per k6 VU,
more VUs than Tomcat's 8192 connection limit), not from the app. Say how it was found: the pool held a
connection for a fraction of a millisecond, so the waits were not database waits.

## 2:40 – 3:00 Close

Say what you would do next: run the same experiments on AWS behind an ALB, and kill one ECS task under load.
