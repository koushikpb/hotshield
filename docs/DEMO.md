# Demo video script (about 3 minutes)

Record on one machine with the cluster up. Every number on screen comes from the README results or from the
run you do while recording; don't read numbers from this file aloud as if they were live.

Before recording:

```bash
docker compose --profile cluster up -d --build   # the nodes run Flyway, which creates the table
docker compose exec -T postgres psql -U hotshield hotshield < loadtest/seed.sql
```

## 0:00 – 0:30 The problem

Show the README diagram. Say: a hot key sends the same read to Postgres thousands of times a second, and
when its cached copy is dropped, every concurrent reader misses at once. HotShield sits in front of
Postgres, caches popular keys, and collapses concurrent misses for one key into one query.

## 0:30 – 1:15 Coalescing

Open `SingleFlight` and the race test next to it. Say: the first caller for a key becomes the leader and
runs the query; the others wait on the same future. Point at the test: 1,000 virtual threads call `run()`
for one key, the leader's loader holds until every other caller is parked inside `run()`, and the loader
runs once.

Show the herd table in the README: after the PUT, cache-only mode sent 152 queries in a second and the
burst p99 was 1064.8 ms; with coalescing it was 63 queries and 55.8 ms.

## 1:15 – 2:00 Three nodes and invalidation

Show each node's forward counter, read one key that no node has cached yet on each node directly, then
show the counters again:

```bash
for p in 8081 8082 8083; do echo $p; curl -s localhost:$p/actuator/prometheus | grep '^owner_forwards_total.*ok'; done
for p in 8081 8082 8083; do curl -s localhost:$p/items/777 > /dev/null; done
for p in 8081 8082 8083; do echo $p; curl -s localhost:$p/actuator/prometheus | grep '^owner_forwards_total.*ok'; done
```

Say: rendezvous hashing gives each key one owner, so a miss on a non-owner is forwarded to the owner
instead of going to the database. The two non-owners each count one more forward; the owner counts none,
because it read the database itself.

Then do a PUT and read the key again from each node to show the new version:

```bash
curl -s -X PUT localhost:8080/items/42 -H 'Content-Type: application/json' \
  -d '{"name":"item 42 demo","priceCents":4200,"stock":42}'
for p in 8081 8082 8083; do curl -s localhost:$p/items/42; echo; done
```

Say what can still go stale: an invalidation sent to a node that is paused is lost. In the staleness
run in `docs/EXPERIMENTS.md`, node-b was paused for 5 s with a 10-minute TTL: 2 reads saw an old version,
the worst 4,120 ms after the PUT, and then the stale reads stopped. That run doesn't show which mechanism
ended them.

## 2:00 – 2:40 Measurements

Show the zipf table: 3000 req/s, the cache cut DB queries per request from 1.0000 to 0.2251. Say that
coalescing adds nothing on zipf because concurrent misses for one key are rare there; it matters on the herd.

Show the milestone 12 entry: direct mode failed at 4000 req/s, and the cause was mostly the load
generator. In the warmup, k6 started 10,000 VUs and took the CPU, and the pool saturated. In the measured
phase, about 17,000 requests hit k6's 5 s timeout, most likely because 10,000 VUs meant more connections
than Tomcat's 8192. Postgres took 0.024 ms per query. With VUs sized for the real concurrency, direct mode
ran 6000 req/s with 0 errors.

## 2:40 – 3:00 AWS and close

Show the AWS table in the README: the same order on three Fargate tasks behind an ALB at 500 req/s. Say that
stopping one task caused no failed requests (k6 still dropped 16 iterations); the other nodes read the
database for its keys for about 172 s, until shortly after the replacement was registered, and most of that
time was ECS deregistering, draining and replacing the task. Close with what you would change next: a
shorter ALB deregistration delay (it saves at most 30 s), and per-second error counts in k6.
