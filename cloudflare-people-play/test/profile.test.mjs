import assert from "node:assert/strict";
import test from "node:test";
import {
  ProfileResolutionError,
  resolveCanonicalPeoplePlayProfile,
} from "../dist-test/src/profile.js";

const identity = {
  userId: "00000000-0000-4000-8000-00000000000a",
  accessToken: "user-access-token-fixture",
};

const json = (value, status = 200) => Response.json(value, { status });

function sequenceFetch(responses, requests = []) {
  return async (input, init) => {
    requests.push({ url: String(input), init });
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return response;
  };
}

async function expectCode(promise, code) {
  await assert.rejects(promise, (error) => error instanceof ProfileResolutionError && error.code === code);
}

test("canonical profile lookup uses own UUID, end-user JWT and anon key", async () => {
  const requests = [];
  const fetcher = sequenceFetch([
    json([{ display_name_id: 42 }]),
    json([{ id: 42, display_name: "Fixture Canonical" }]),
  ], requests);
  assert.deepEqual(
    await resolveCanonicalPeoplePlayProfile(identity, "https://project.example/", "anon-fixture", fetcher),
    { displayName: "Fixture Canonical" },
  );
  assert.equal(requests.length, 2);
  const profileUrl = new URL(requests[0].url);
  assert.equal(profileUrl.pathname, "/rest/v1/play_profiles");
  assert.equal(profileUrl.searchParams.get("select"), "display_name_id");
  assert.equal(profileUrl.searchParams.get("user_id"), `eq.${identity.userId}`);
  assert.equal(profileUrl.searchParams.get("limit"), "2");
  const nameUrl = new URL(requests[1].url);
  assert.equal(nameUrl.pathname, "/rest/v1/play_display_names");
  assert.equal(nameUrl.searchParams.get("select"), "id,display_name");
  assert.equal(nameUrl.searchParams.get("id"), "eq.42");
  assert.equal(nameUrl.searchParams.has("is_active"), false);
  for (const request of requests) {
    const headers = new Headers(request.init.headers);
    assert.equal(headers.get("apikey"), "anon-fixture");
    assert.equal(headers.get("authorization"), `Bearer ${identity.accessToken}`);
    assert.notEqual(headers.get("authorization"), "Bearer anon-fixture");
    assert.equal(request.init.body, undefined);
    assert.equal(request.url.includes("displayName"), false);
  }
});

test("profile absence requires profile creation", async () => {
  await expectCode(
    resolveCanonicalPeoplePlayProfile(identity, "https://project.example", "anon", sequenceFetch([json([])])),
    "PROFILE_REQUIRED",
  );
});

test("multiple profiles are unavailable", async () => {
  await expectCode(
    resolveCanonicalPeoplePlayProfile(identity, "https://project.example", "anon", sequenceFetch([
      json([{ display_name_id: 1 }, { display_name_id: 2 }]),
    ])),
    "PROFILE_UNAVAILABLE",
  );
});

test("missing, multiple and blank canonical names are unavailable", async () => {
  for (const names of [
    [],
    [{ id: 1, display_name: "One" }, { id: 1, display_name: "Two" }],
    [{ id: 1, display_name: "   " }],
  ]) {
    await expectCode(
      resolveCanonicalPeoplePlayProfile(identity, "https://project.example", "anon", sequenceFetch([
        json([{ display_name_id: 1 }]),
        json(names),
      ])),
      "PROFILE_UNAVAILABLE",
    );
  }
});

test("Supabase HTTP and transport failures are unavailable", async () => {
  for (const responses of [
    [json({ error: "hidden" }, 500)],
    [new Error("network")],
    [json([{ display_name_id: 1 }]), json({ error: "hidden" }, 403)],
  ]) {
    await expectCode(
      resolveCanonicalPeoplePlayProfile(identity, "https://project.example", "anon", sequenceFetch(responses)),
      "PROFILE_UNAVAILABLE",
    );
  }
});
