import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { once } from "node:events";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";
import { fileURLToPath } from "node:url";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const registryName = "people-play-room-registry";
const authHeaders = { authorization: "Bearer local-test-token" };
let worker;
let baseUrl;
let output = "";
let persistDir;

async function freePort() {
  const server = net.createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return address.port;
}

async function waitForWorker() {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (worker.exitCode !== null) throw new Error(`Local Wrangler exited (${worker.exitCode}):\n${output}`);
    try {
      const response = await fetch(`${baseUrl}/__test/health`);
      if (response.ok) return;
    } catch {
      // Wrangler is still starting its local runtime.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out starting local Wrangler:\n${output}`);
}

async function post(pathname, body = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

async function rawGet(pathname) {
  return new Promise((resolve, reject) => {
    const request = http.get(`${baseUrl}${pathname}`, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => resolve({ status: response.statusCode, headers: response.headers, body: JSON.parse(body) }));
    });
    request.on("error", reject);
  });
}

async function registryList() {
  const result = await post("/__test/registry/list");
  assert.equal(result.status, 200);
  return result.body.rooms;
}

async function allocate() {
  const result = await post("/__test/registry/allocate");
  assert.equal(result.status, 200);
  assert.match(result.body.roomId, /^[0-9a-f]{8}-[0-9a-f-]{27}$/i);
  return result.body.roomId;
}

const member = (memberId, displayName) => ({ memberId, displayName, avatarId: "GIRL" });
const projection = (roomId, phase = "WAITING", spectatorCount = 1) => phase === "WAITING"
  ? {
      roomId,
      timeControl: "FIFTEEN_MINUTES",
      phase,
      seats: { a: member("member-a", "Aster"), b: null },
      players: null,
      spectatorCount,
    }
  : {
      roomId,
      timeControl: "FIFTEEN_MINUTES",
      phase,
      seats: { a: member("member-a", "Aster"), b: member("member-b", "Birch") },
      players: { black: member("member-b", "Birch"), white: member("member-a", "Aster") },
      spectatorCount,
    };

before(async () => {
  const port = await freePort();
  persistDir = await mkdtemp(path.join(os.tmpdir(), "people-play-wrangler-"));
  baseUrl = `http://127.0.0.1:${port}`;
  const wranglerPath = path.join(packageRoot, "node_modules", "wrangler", "bin", "wrangler.js");
  worker = spawn(process.execPath, [
    wranglerPath,
    "dev",
    "--local",
    "--config",
    "test/wrangler.local.jsonc",
    "--port",
    String(port),
    "--ip",
    "127.0.0.1",
    "--persist-to",
    persistDir,
    "--log-level",
    "error",
  ], { cwd: packageRoot, detached: process.platform !== "win32", windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
  worker.stdout.setEncoding("utf8").on("data", (chunk) => { output = (output + chunk).slice(-10_000); });
  worker.stderr.setEncoding("utf8").on("data", (chunk) => { output = (output + chunk).slice(-10_000); });
  await waitForWorker();
});

after(async () => {
  if (worker && worker.exitCode === null) {
    if (process.platform === "win32") {
      try { execFileSync("taskkill", ["/pid", String(worker.pid), "/T", "/F"], { stdio: "ignore" }); } catch { /* process may already have exited */ }
    } else {
      try { process.kill(-worker.pid, "SIGTERM"); } catch { /* process may already have exited */ }
    }
    await Promise.race([once(worker, "exit"), new Promise((resolve) => setTimeout(resolve, 10_000))]);
  }
  if (persistDir && path.dirname(persistDir) === os.tmpdir() && path.basename(persistDir).startsWith("people-play-wrangler-")) {
    await rm(persistDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  }
});

test("local Wrangler HTTP contract authenticates GET rooms and rejects other routes/methods", async () => {
  const noToken = await fetch(`${baseUrl}/v1/people-play/rooms`);
  assert.equal(noToken.status, 401);
  assert.deepEqual(await noToken.json(), { error: "AUTH_REQUIRED" });

  for (const authorization of ["Basic abc", "Bearer", "Bearer invalid-token"]) {
    const denied = await fetch(`${baseUrl}/v1/people-play/rooms`, { headers: { authorization } });
    assert.equal(denied.status, 401);
    assert.deepEqual(await denied.json(), { error: "AUTH_REQUIRED" });
  }

  const empty = await fetch(`${baseUrl}/v1/people-play/rooms`, { headers: authHeaders });
  assert.equal(empty.status, 200);
  assert.match(empty.headers.get("content-type") ?? "", /^application\/json/);
  assert.deepEqual(await empty.json(), { rooms: [] });

  const wrongMethod = await fetch(`${baseUrl}/v1/people-play/rooms`, { method: "POST" });
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get("allow"), "GET");
  assert.equal((await fetch(`${baseUrl}/unknown`)).status, 404);
  for (const route of ["/v1/people-play/rooms/new/socket", "/v1/people-play/rooms/a/socket"]) {
    const response = await rawGet(route);
    assert.equal(response.status, 426);
    assert.deepEqual(response.body, { error: "UPGRADE_REQUIRED" });
  }
  const inspectedUpgrade = await fetch(`${baseUrl}/__test/upgrade-required-header`);
  assert.deepEqual(await inspectedUpgrade.json(), { status: 426, upgrade: "websocket" });
  const wrongSocketMethod = await fetch(`${baseUrl}/v1/people-play/rooms/new/socket`, { method: "POST" });
  assert.equal(wrongSocketMethod.status, 405);
  assert.equal(wrongSocketMethod.headers.get("allow"), "GET");
});

test("social availability persists per user and room events dedupe one notification per slot", async () => {
  const slotStart = Math.ceil(Date.now() / (30 * 60 * 1000)) * (30 * 60 * 1000);
  const slotQuery = new URLSearchParams([["slotStart", String(slotStart)]]).toString();

  const denied = await fetch(`${baseUrl}/v1/people-social/availability?${slotQuery}`);
  assert.equal(denied.status, 401);

  const setResponse = await fetch(`${baseUrl}/v1/people-social/availability`, {
    method: "PUT",
    headers: {
      authorization: "Bearer test-user-b",
      "content-type": "application/json",
    },
    body: JSON.stringify({ slotStart, enabled: true }),
  });
  assert.equal(setResponse.status, 200);
  assert.deepEqual(await setResponse.json(), {
    slot: { slotStart, people: 1, selected: true },
  });

  const observer = await fetch(`${baseUrl}/v1/people-social/availability?${slotQuery}`, {
    headers: { authorization: "Bearer test-user-a" },
  });
  assert.equal(observer.status, 200);
  assert.deepEqual(await observer.json(), {
    slots: [{ slotStart, people: 1, selected: false }],
  });

  const owner = await fetch(`${baseUrl}/v1/people-social/availability?${slotQuery}`, {
    headers: { authorization: "Bearer test-user-b" },
  });
  assert.deepEqual(await owner.json(), {
    slots: [{ slotStart, people: 1, selected: true }],
  });

  const push = await fetch(`${baseUrl}/v1/people-social/push-device`, {
    method: "PUT",
    headers: {
      authorization: "Bearer test-user-b",
      "content-type": "application/json",
    },
    body: JSON.stringify({ token: "fixture-device-token-000000000001" }),
  });
  assert.equal(push.status, 200);
  assert.deepEqual(await push.json(), { ok: true });

  const creatorUserId = "00000000-0000-4000-8000-00000000000a";
  const firstRoom = await post("/__test/social/record-room-created", {
    roomId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    creatorUserId,
    createdAt: slotStart + 60_000,
  });
  assert.deepEqual(firstRoom, { status: 200, body: { queued: 1 } });

  const secondRoom = await post("/__test/social/record-room-created", {
    roomId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    creatorUserId,
    createdAt: slotStart + 120_000,
  });
  assert.deepEqual(secondRoom, { status: 200, body: { queued: 0 } });

  const pending = await fetch(`${baseUrl}/__test/social/pending-count`);
  assert.deepEqual(await pending.json(), { count: 1 });

  const unset = await fetch(`${baseUrl}/v1/people-social/availability`, {
    method: "PUT",
    headers: {
      authorization: "Bearer test-user-b",
      "content-type": "application/json",
    },
    body: JSON.stringify({ slotStart, enabled: false }),
  });
  assert.equal(unset.status, 200);
  assert.deepEqual(await unset.json(), {
    slot: { slotStart, people: 0, selected: false },
  });
});



test("social notification queue transfers device ownership, cancels on opt-out, groups devices, and retries stale claims", async () => {
  const slotStart = Math.ceil(Date.now() / (30 * 60 * 1000)) * (30 * 60 * 1000) + (30 * 60 * 1000);
  const createdAt = slotStart + 60_000;
  const authB = {
    authorization: "Bearer test-user-b",
    "content-type": "application/json",
  };
  const authC = {
    authorization: "Bearer test-user-c",
    "content-type": "application/json",
  };

  const enable = await fetch(`${baseUrl}/v1/people-social/availability`, {
    method: "PUT",
    headers: authB,
    body: JSON.stringify({ slotStart, enabled: true }),
  });
  assert.equal(enable.status, 200);

  const sharedToken = "fixture-shared-device-token-000000001";
  assert.equal((await fetch(`${baseUrl}/v1/people-social/push-device`, {
    method: "PUT",
    headers: authB,
    body: JSON.stringify({ token: sharedToken }),
  })).status, 200);
  assert.equal((await fetch(`${baseUrl}/v1/people-social/push-device`, {
    method: "PUT",
    headers: authC,
    body: JSON.stringify({ token: sharedToken }),
  })).status, 200);

  const creatorUserId = "00000000-0000-4000-8000-00000000000a";
  const transferred = await post("/__test/social/record-room-created", {
    roomId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    creatorUserId,
    createdAt,
  });
  assert.deepEqual(transferred, { status: 200, body: { queued: 0 } });

  const tokenOne = "fixture-user-b-device-token-000000001";
  const tokenTwo = "fixture-user-b-device-token-000000002";
  for (const token of [tokenOne, tokenTwo]) {
    const registered = await fetch(`${baseUrl}/v1/people-social/push-device`, {
      method: "PUT",
      headers: authB,
      body: JSON.stringify({ token }),
    });
    assert.equal(registered.status, 200);
  }

  const queued = await post("/__test/social/record-room-created", {
    roomId: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    creatorUserId,
    createdAt: createdAt + 60_000,
  });
  assert.deepEqual(queued, { status: 200, body: { queued: 1 } });
  assert.deepEqual(await (await fetch(`${baseUrl}/__test/social/pending-count`)).json(), { count: 1 });

  const disabled = await fetch(`${baseUrl}/v1/people-social/availability`, {
    method: "PUT",
    headers: authB,
    body: JSON.stringify({ slotStart, enabled: false }),
  });
  assert.equal(disabled.status, 200);
  assert.deepEqual(await (await fetch(`${baseUrl}/__test/social/pending-count`)).json(), { count: 0 });

  const reenabled = await fetch(`${baseUrl}/v1/people-social/availability`, {
    method: "PUT",
    headers: authB,
    body: JSON.stringify({ slotStart, enabled: true }),
  });
  assert.equal(reenabled.status, 200);

  const requeued = await post("/__test/social/record-room-created", {
    roomId: "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    creatorUserId,
    createdAt: createdAt + 120_000,
  });
  assert.deepEqual(requeued, { status: 200, body: { queued: 1 } });

  const firstClaimAt = createdAt + 180_000;
  const firstClaim = await post("/__test/social/claim-pending", { now: firstClaimAt, limit: 1 });
  assert.equal(firstClaim.status, 200);
  assert.equal(firstClaim.body.notifications.length, 1);
  assert.equal(firstClaim.body.notifications[0].targetUserId, "00000001-0000-4000-8000-00000000000b");
  assert.deepEqual(
    [...firstClaim.body.notifications[0].tokens].sort(),
    [tokenOne, tokenTwo].sort(),
  );

  const immediateRetry = await post("/__test/social/claim-pending", {
    now: firstClaimAt + 60_000,
    limit: 50,
  });
  assert.deepEqual(immediateRetry.body.notifications, []);

  const staleRetry = await post("/__test/social/claim-pending", {
    now: firstClaimAt + (5 * 60 * 1000) + 1,
    limit: 50,
  });
  assert.equal(staleRetry.body.notifications.length, 1);
  assert.deepEqual(
    [...staleRetry.body.notifications[0].tokens].sort(),
    [tokenOne, tokenTwo].sort(),
  );
});

test("RoomRegistry allocates privately, publishes and replaces only listed projections", async () => {
  const id = await allocate();
  assert.deepEqual(await (await post("/__test/registry/resolve", { roomId: id })).body, { status: "active" });
  assert.deepEqual(await registryList(), []);

  const unknown = await post("/__test/registry/publish", { roomId: "unknown-id", projection: projection("unknown-id") });
  assert.deepEqual(unknown.body, { ok: false, error: "NOT_FOUND" });

  const waiting = projection(id);
  assert.deepEqual((await post("/__test/registry/publish", { roomId: id, projection: waiting })).body, { ok: true });
  assert.deepEqual(await registryList(), [waiting]);
  const reconciled = await fetch(`${baseUrl}/v1/people-play/rooms`, { headers: authHeaders });
  assert.equal(reconciled.status, 200);
  assert.equal((await reconciled.json()).rooms.some((room) => room.roomId === id), false);
  assert.deepEqual((await post("/__test/registry/publish", { roomId: id, projection: waiting })).body, { ok: false, error: "ALREADY_PUBLISHED" });

  const updatedWaiting = { ...waiting, spectatorCount: 3 };
  assert.deepEqual((await post("/__test/registry/update", { roomId: id, projection: updatedWaiting })).body, { ok: true });
  assert.deepEqual(await registryList(), [updatedWaiting]);

  const playing = projection(id, "PLAYING", 2);
  assert.deepEqual((await post("/__test/registry/update", { roomId: id, projection: playing })).body, { ok: true });
  assert.deepEqual(await registryList(), [playing]);
  assert.deepEqual((await post("/__test/registry/close", { roomId: id })).body, { ok: true });
  assert.deepEqual(await registryList(), []);
});

test("issued-only failures stay hidden and publish/update/close enforce lifecycle", async () => {
  const issuedOnly = await allocate();
  assert.deepEqual(await registryList(), []);
  assert.deepEqual((await post("/__test/registry/update", { roomId: issuedOnly, projection: projection(issuedOnly, "PLAYING") })).body, { ok: false, error: "NOT_PUBLISHED" });
  assert.deepEqual((await post("/__test/registry/close", { roomId: issuedOnly })).body, { ok: true });
  assert.deepEqual((await post("/__test/registry/close", { roomId: issuedOnly })).body, { ok: true });
  assert.deepEqual((await post("/__test/registry/resolve", { roomId: issuedOnly })).body, { status: "closed" });
  assert.deepEqual((await post("/__test/registry/publish", { roomId: issuedOnly, projection: projection(issuedOnly) })).body, { ok: false, error: "ROOM_CLOSED" });
  assert.deepEqual((await post("/__test/registry/update", { roomId: issuedOnly, projection: projection(issuedOnly) })).body, { ok: false, error: "ROOM_CLOSED" });
  assert.deepEqual(await registryList(), []);

  assert.deepEqual((await post("/__test/registry/update", { roomId: "missing-id", projection: projection("missing-id") })).body, { ok: false, error: "NOT_FOUND" });
  assert.deepEqual((await post("/__test/registry/close", { roomId: "missing-id" })).body, { ok: false, error: "NOT_FOUND" });
  assert.deepEqual((await post("/__test/registry/resolve", { roomId: "missing-id" })).body, { status: "unknown" });

  const activeId = await allocate();
  const activeProjection = projection(activeId);
  assert.deepEqual((await post("/__test/registry/publish", { roomId: activeId, projection: activeProjection })).body, { ok: true });
  assert.equal((await registryList()).length, 1);
  assert.deepEqual((await post("/__test/registry/close", { roomId: activeId })).body, { ok: true });
  assert.deepEqual(await registryList(), []);
  assert.deepEqual((await post("/__test/registry/resolve", { roomId: activeId })).body, { status: "closed" });
  assert.deepEqual((await post("/__test/registry/update", { roomId: activeId, projection: projection(activeId, "PLAYING") })).body, { ok: false, error: "ROOM_CLOSED" });
  assert.deepEqual(await registryList(), []);

  const laterId = await allocate();
  assert.notEqual(laterId, activeId);
  assert.deepEqual(await registryList(), []);
});

test("invalid projections never publish or replace an existing projection", async () => {
  const id = await allocate();
  const waiting = projection(id);
  assert.deepEqual((await post("/__test/registry/publish", { roomId: id, projection: { ...waiting, phase: "CLOSED" } })).body, { ok: false, error: "INVALID_PROJECTION" });
  assert.deepEqual((await post("/__test/registry/publish", { roomId: id, projection: waiting })).body, { ok: true });
  assert.deepEqual((await post("/__test/registry/update", { roomId: id, projection: { ...waiting, seats: { a: member("a", "A"), b: member("b", "B") } } })).body, { ok: false, error: "INVALID_PROJECTION" });
  assert.deepEqual(await registryList(), [waiting]);
});
