import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { once } from "node:events";
import { readFile, mkdtemp, rm } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";
import { fileURLToPath } from "node:url";
import WebSocket from "ws";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const takeSeatFixture = JSON.parse(await readFile(new URL("../../protocol/people-play/v1/fixtures/client/take-seat.json", import.meta.url), "utf8"));
const leaveSeatFixture = JSON.parse(await readFile(new URL("../../protocol/people-play/v1/fixtures/client/leave-seat.json", import.meta.url), "utf8"));
const moveFixture = JSON.parse(await readFile(new URL("../../protocol/people-play/v1/fixtures/client/move-snapshot.json", import.meta.url), "utf8"));
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
      // Wrangler is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out starting local Wrangler:\n${output}`);
}

function webSocketUrl(pathname) {
  return `${baseUrl.replace(/^http/, "ws")}${pathname}`;
}

function wrapSocket(ws, headers) {
  const queue = [];
  const waiters = [];
  const history = [];
  ws.on("message", (data, isBinary) => {
    const value = isBinary ? data : JSON.parse(data.toString());
    history.push(value);
    const index = waiters.findIndex(({ predicate }) => predicate(value));
    if (index >= 0) {
      const [{ resolve, timer }] = waiters.splice(index, 1);
      clearTimeout(timer);
      resolve(value);
    } else {
      queue.push(value);
    }
  });
  return {
    ws,
    headers,
    history,
    async next(predicate = () => true, timeoutMs = 8_000) {
      const index = queue.findIndex(predicate);
      if (index >= 0) return queue.splice(index, 1)[0];
      return new Promise((resolve, reject) => {
        const waiter = { predicate, resolve, timer: null };
        waiter.timer = setTimeout(() => {
          const position = waiters.indexOf(waiter);
          if (position >= 0) waiters.splice(position, 1);
          reject(new Error(`Timed out waiting for WebSocket message; history=${JSON.stringify(history)}`));
        }, timeoutMs);
        waiters.push(waiter);
      });
    },
    send(type, roomId) {
      const source = type === "TAKE_SEAT" ? takeSeatFixture : leaveSeatFixture;
      ws.send(JSON.stringify({ ...source, roomId }));
    },
  };
}

async function openSocket(pathname, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(webSocketUrl(pathname), { headers: { authorization: `Bearer ${token}` } });
    let responseHeaders;
    ws.once("upgrade", (response) => { responseHeaders = response.headers; });
    ws.once("open", () => resolve(wrapSocket(ws, responseHeaders)));
    ws.once("unexpected-response", (_request, response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => reject(new Error(`Unexpected ${response.statusCode}: ${body}`)));
    });
    ws.once("error", reject);
  });
}

async function rejectedSocket(pathname, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(webSocketUrl(pathname), { headers: { authorization: `Bearer ${token}` } });
    ws.once("unexpected-response", (_request, response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => {
        ws.removeAllListeners();
        resolve({ status: response.statusCode, body: JSON.parse(body) });
      });
    });
    ws.once("open", () => reject(new Error("Expected upgrade rejection")));
    ws.once("error", () => { /* unexpected-response is authoritative */ });
  });
}

async function closeSocket(client) {
  if (client.ws.readyState === WebSocket.CLOSED) return;
  const closed = once(client.ws, "close");
  client.ws.close();
  await Promise.race([closed, new Promise((resolve) => setTimeout(resolve, 5_000))]);
}

async function post(pathname, body = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}

async function rooms() {
  const response = await fetch(`${baseUrl}/v1/people-play/rooms`, {
    headers: { authorization: "Bearer local-test-token" },
  });
  assert.equal(response.status, 200);
  return (await response.json()).rooms;
}

async function waitUntil(predicate, timeoutMs = 8_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Timed out waiting for condition");
}

before(async () => {
  const port = await freePort();
  persistDir = await mkdtemp(path.join(os.tmpdir(), "people-play-room-wrangler-"));
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
  worker.stdout.setEncoding("utf8").on("data", (chunk) => { output = (output + chunk).slice(-20_000); });
  worker.stderr.setEncoding("utf8").on("data", (chunk) => { output = (output + chunk).slice(-20_000); });
  await waitForWorker();
});

after(async () => {
  if (worker && worker.exitCode === null) {
    if (process.platform === "win32") {
      try { execFileSync("taskkill", ["/pid", String(worker.pid), "/T", "/F"], { stdio: "ignore" }); } catch { /* stopped */ }
    } else {
      try { process.kill(-worker.pid, "SIGTERM"); } catch { /* stopped */ }
    }
    await Promise.race([once(worker, "exit"), new Promise((resolve) => setTimeout(resolve, 10_000))]);
  }
  if (persistDir && path.dirname(persistDir) === os.tmpdir() && path.basename(persistDir).startsWith("people-play-room-wrangler-")) {
    await rm(persistDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 });
  }
});

test("creator, spectator join and second seat start PLAYING with response member IDs", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=TEN_MINUTES", "test-user-a");
  const creatorMemberId = creator.headers["x-people-play-member-id"];
  assert.match(creatorMemberId, /^[0-9a-f-]{36}$/i);
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  assert.equal(initial.phase, "WAITING");
  assert.equal(initial.seats.a.memberId, creatorMemberId);
  assert.equal(initial.seats.a.displayName, "サンプル・アルファ");
  assert.equal(initial.seats.b, null);
  assert.equal(initial.players, null);
  assert.equal(initial.spectatorCount, 0);
  assert.equal(initial.currentPly, 0);
  assert.equal(initial.nextTurn, "BLACK");
  assert.equal(initial.board.length, 64);
  const roomId = initial.roomId;
  assert.equal((await rooms()).find((room) => room.roomId === roomId).phase, "WAITING");

  const joiner = await openSocket(`/v1/people-play/rooms/${roomId}/socket`, "test-user-b");
  const joinerMemberId = joiner.headers["x-people-play-member-id"];
  assert.notEqual(joinerMemberId, creatorMemberId);
  const joined = await joiner.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 1);
  assert.equal(joined.seats.b, null);
  assert.equal(joined.spectatorAvatarPreview.length, 1);
  await creator.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 1);

  const historyStart = creator.history.length;
  joiner.send("TAKE_SEAT", roomId);
  const playing = await joiner.next((message) => message.type === "ROOM_SNAPSHOT" && message.phase === "PLAYING");
  assert.equal(playing.seats.b.memberId, joinerMemberId);
  assert.equal(new Set([playing.players.black.memberId, playing.players.white.memberId]).size, 2);
  assert.deepEqual(
    new Set([playing.players.black.memberId, playing.players.white.memberId]),
    new Set([creatorMemberId, joinerMemberId]),
  );
  assert.equal(playing.nextTurn, "BLACK");
  assert.equal(playing.spectatorCount, 0);
  await creator.next((message) => message.type === "ROOM_SNAPSHOT" && message.phase === "PLAYING");
  assert.equal(creator.history.slice(historyStart).some((message) => message.phase === "WAITING" && message.seats.a && message.seats.b), false);
  await waitUntil(async () => (await rooms()).find((room) => room.roomId === roomId)?.phase === "PLAYING");
  await closeSocket(joiner);
  await closeSocket(creator);
});

test("free seating has no owner priority and duplicate accounts remain distinct sockets", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=FIVE_MINUTES", "test-user-a");
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  const roomId = initial.roomId;
  creator.send("LEAVE_SEAT", roomId);
  const empty = await creator.next((message) => message.type === "ROOM_SNAPSHOT" && message.seats.a === null);
  assert.equal(empty.spectatorCount, 1);

  const duplicate = await openSocket(`/v1/people-play/rooms/${roomId}/socket`, "test-user-a-duplicate");
  const duplicateId = duplicate.headers["x-people-play-member-id"];
  assert.notEqual(duplicateId, creator.headers["x-people-play-member-id"]);
  duplicate.send("TAKE_SEAT", roomId);
  const firstSeat = await duplicate.next((message) => message.type === "ROOM_SNAPSHOT" && message.seats.a?.memberId === duplicateId);
  assert.equal(firstSeat.phase, "WAITING");
  creator.send("TAKE_SEAT", roomId);
  const playing = await creator.next((message) => message.type === "ROOM_SNAPSHOT" && message.phase === "PLAYING");
  assert.equal(playing.seats.a.memberId, duplicateId);
  assert.equal(playing.seats.b.memberId, creator.headers["x-people-play-member-id"]);
  assert.equal(playing.seats.a.avatarId, playing.seats.b.avatarId);
  await closeSocket(duplicate);
  await closeSocket(creator);
});

test("WAITING disconnect clears seats, rejoin gets new identity, zero members close the room", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=THREE_MINUTES", "test-user-a");
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  const roomId = initial.roomId;
  const oldMemberId = creator.headers["x-people-play-member-id"];
  const spectator = await openSocket(`/v1/people-play/rooms/${roomId}/socket`, "test-user-b");
  await spectator.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 1);
  await closeSocket(creator);
  const cleared = await spectator.next((message) => message.type === "ROOM_SNAPSHOT" && message.seats.a === null);
  assert.equal(cleared.spectatorCount, 1);

  const rejoined = await openSocket(`/v1/people-play/rooms/${roomId}/socket`, "test-user-a");
  assert.notEqual(rejoined.headers["x-people-play-member-id"], oldMemberId);
  const rejoinSnapshot = await rejoined.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 2);
  assert.equal(rejoinSnapshot.seats.a, null);
  await closeSocket(rejoined);
  await spectator.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 1);
  await closeSocket(spectator);
  await waitUntil(async () => !(await rooms()).some((room) => room.roomId === roomId));
  assert.deepEqual(await rejectedSocket(`/v1/people-play/rooms/${roomId}/socket`, "test-user-a"), {
    status: 410,
    body: { error: "ROOM_CLOSED" },
  });
});

test("unknown and issued-only room IDs cannot initialize Room DOs", async () => {
  assert.deepEqual(await rejectedSocket("/v1/people-play/rooms/new/socket?timeControl=TEN_MINUTES", "invalid-token"), {
    status: 401,
    body: { error: "AUTH_REQUIRED" },
  });
  assert.deepEqual(await rejectedSocket("/v1/people-play/rooms/new/socket?timeControl=ONE_MINUTE", "test-user-a"), {
    status: 400,
    body: { error: "BAD_TIME_CONTROL" },
  });
  const unknown = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  assert.deepEqual(await rejectedSocket(`/v1/people-play/rooms/${unknown}/socket`, "test-user-a"), {
    status: 404,
    body: { error: "ROOM_NOT_FOUND" },
  });
  const allocated = await post("/__test/registry/allocate");
  assert.equal(allocated.status, 200);
  assert.deepEqual(await rejectedSocket(`/v1/people-play/rooms/${allocated.body.roomId}/socket`, "test-user-a"), {
    status: 404,
    body: { error: "ROOM_NOT_FOUND" },
  });
  assert.deepEqual((await post("/__test/registry/resolve-join-target", { roomId: allocated.body.roomId })).body, { status: "unknown" });
});

test("Phase 3 parser rejects binary, unknown fields, versions and Phase 4 messages without mutation", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=TEN_MINUTES", "test-user-a");
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  const roomId = initial.roomId;
  const cases = [
    { payload: Buffer.from([1, 2, 3]), code: "BAD_MESSAGE", rejectedType: "UNKNOWN" },
    { payload: JSON.stringify({ ...takeSeatFixture, roomId, extra: true }), code: "BAD_MESSAGE", rejectedType: "TAKE_SEAT" },
    { payload: JSON.stringify({ ...takeSeatFixture, roomId, protocolVersion: 2 }), code: "UNSUPPORTED_VERSION", rejectedType: "TAKE_SEAT" },
    { payload: JSON.stringify({ ...moveFixture, roomId }), code: "BAD_MESSAGE", rejectedType: "MOVE_SNAPSHOT" },
  ];
  for (const entry of cases) {
    creator.ws.send(entry.payload);
    const error = await creator.next((message) => message.type === "ERROR" && message.code === entry.code);
    assert.equal(error.rejectedType, entry.rejectedType);
    assert.equal(error.currentPly, 0);
    assert.equal(error.rejectedPly, null);
  }
  assert.equal(creator.history.some((message) => message.type === "ROOM_SNAPSHOT" && message.phase !== "WAITING"), false);
  await closeSocket(creator);
});

test("four spectators produce an exact count and a memberId-sorted three-avatar preview", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=FIFTEEN_MINUTES", "test-user-a");
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  const roomId = initial.roomId;
  const joined = [];
  const tokenToAvatar = new Map([
    ["test-user-b", "ADULT_WOMAN"],
    ["test-user-c", "BOY"],
    ["test-user-d", "GIRL"],
    ["test-user-e", "MAGIC_WAND"],
  ]);
  for (const token of tokenToAvatar.keys()) {
    const client = await openSocket(`/v1/people-play/rooms/${roomId}/socket`, token);
    joined.push({ client, token, memberId: client.headers["x-people-play-member-id"] });
  }
  const final = await joined.at(-1).client.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 4);
  const expected = [...joined]
    .sort((left, right) => left.memberId.localeCompare(right.memberId))
    .slice(0, 3)
    .map(({ token }) => tokenToAvatar.get(token));
  assert.equal(final.spectatorCount, 4);
  assert.deepEqual(final.spectatorAvatarPreview, expected);
  for (const { client } of joined) await closeSocket(client);
  await closeSocket(creator);
});

test("25 seconds idle preserves socket member identity for a later seat command", async () => {
  const creator = await openSocket("/v1/people-play/rooms/new/socket?timeControl=TWENTY_MINUTES", "test-user-a");
  const initial = await creator.next((message) => message.type === "ROOM_SNAPSHOT");
  const joiner = await openSocket(`/v1/people-play/rooms/${initial.roomId}/socket`, "test-user-b");
  const memberId = joiner.headers["x-people-play-member-id"];
  await joiner.next((message) => message.type === "ROOM_SNAPSHOT" && message.spectatorCount === 1);
  await new Promise((resolve) => setTimeout(resolve, 25_000));
  joiner.send("TAKE_SEAT", initial.roomId);
  const playing = await joiner.next((message) => message.type === "ROOM_SNAPSHOT" && message.phase === "PLAYING");
  assert.ok(playing.players.black.memberId === memberId || playing.players.white.memberId === memberId);
  await closeSocket(joiner);
  await closeSocket(creator);
});
