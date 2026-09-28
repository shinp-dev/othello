import assert from "node:assert/strict";
import test from "node:test";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from "jose";
import { verifySupabaseTokenWithKeyResolver } from "../dist-test/src/auth.js";

const issuer = "https://example.supabase.co/auth/v1";
const subject = "c1a8016d-e654-4c55-9ca0-4dcbfb089f0a";

test("ES256 authenticated token verifies and yields its subject", async () => {
  const { publicKey, privateKey } = await generateKeyPair("ES256");
  const jwk = await exportJWK(publicKey);
  const keyResolver = createLocalJWKSet({ keys: [{ ...jwk, kid: "test-key", alg: "ES256", use: "sig" }] });
  const now = Math.floor(Date.now() / 1000);
  const token = await new SignJWT({ role: "authenticated" })
    .setProtectedHeader({ alg: "ES256", kid: "test-key" })
    .setIssuer(issuer)
    .setAudience("authenticated")
    .setSubject(subject)
    .setIssuedAt(now)
    .setExpirationTime(now + 60)
    .sign(privateKey);
  assert.deepEqual(await verifySupabaseTokenWithKeyResolver(token, issuer, keyResolver), { userId: subject });
});

test("JWT verifier rejects signature, issuer, audience, expiration and claims failures", async () => {
  const { publicKey, privateKey } = await generateKeyPair("ES256");
  const other = await generateKeyPair("ES256");
  const jwk = await exportJWK(publicKey);
  const keyResolver = createLocalJWKSet({ keys: [{ ...jwk, kid: "test-key", alg: "ES256", use: "sig" }] });
  const now = Math.floor(Date.now() / 1000);
  const token = (options = {}) => {
    const header = options.header ?? { alg: "ES256", kid: "test-key" };
    const signingKey = options.key ?? (header.alg === "HS256" ? new Uint8Array(32).fill(7) : privateKey);
    return new SignJWT({ role: "authenticated", ...options.payload })
      .setProtectedHeader(header)
      .setIssuer(options.issuer ?? issuer)
      .setAudience(options.audience ?? "authenticated")
      .setSubject(options.subject ?? subject)
      .setIssuedAt(now)
      .setExpirationTime(options.expiration ?? now + 60)
      .sign(signingKey);
  };

  const invalidTokens = [
    await token({ key: other.privateKey }),
    await token({ issuer: "https://wrong.example/auth/v1" }),
    await token({ audience: "other" }),
    await token({ expiration: now - 1 }),
    await token({ payload: { role: "anon" } }),
    await token({ subject: "not-a-uuid" }),
    await token({ header: { alg: "HS256", kid: "test-key" } }),
    await token({ header: { alg: "ES256" } }),
  ];
  for (const invalid of invalidTokens) await assert.rejects(verifySupabaseTokenWithKeyResolver(invalid, issuer, keyResolver));
});
