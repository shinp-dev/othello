import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";

export interface VerifiedSupabaseUser {
  userId: string;
}

export interface AuthenticatedPeoplePlayRequest extends VerifiedSupabaseUser {
  accessToken: string;
}

const jwksByIssuer = new Map<string, JWTVerifyGetKey>();
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function verifySupabaseAccessToken(
  token: string,
  supabaseUrl: string,
): Promise<VerifiedSupabaseUser> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const issuer = `${baseUrl}/auth/v1`;
  const keyResolver = jwksByIssuer.get(issuer) ?? createRemoteJWKSet(
    new URL(`${issuer}/.well-known/jwks.json`),
  );
  if (!jwksByIssuer.has(issuer)) jwksByIssuer.set(issuer, keyResolver);

  return verifySupabaseTokenWithKeyResolver(token, issuer, keyResolver);
}

export async function verifySupabaseTokenWithKeyResolver(
  token: string,
  issuer: string,
  keyResolver: JWTVerifyGetKey,
): Promise<VerifiedSupabaseUser> {
  const { protectedHeader, payload } = await jwtVerify(token, keyResolver, {
    algorithms: ["ES256"],
    issuer,
    audience: "authenticated",
    requiredClaims: ["iss", "aud", "exp", "sub", "role"],
  });

  if (protectedHeader.alg !== "ES256" || typeof protectedHeader.kid !== "string" || protectedHeader.kid.length === 0) {
    throw new Error("Invalid signing key header");
  }
  if (payload.role !== "authenticated" || typeof payload.sub !== "string" || !UUID_PATTERN.test(payload.sub)) {
    throw new Error("Invalid authenticated subject");
  }

  return { userId: payload.sub };
}

export async function isAuthenticatedPeoplePlayRequest(
  request: Request,
  supabaseUrl: string,
): Promise<boolean> {
  return (await authenticatePeoplePlayRequest(request, supabaseUrl)) !== null;
}

export async function authenticatePeoplePlayRequest(
  request: Request,
  supabaseUrl: string,
): Promise<AuthenticatedPeoplePlayRequest | null> {
  const authorization = request.headers.get("authorization");
  const match = authorization?.match(/^Bearer ([^\s,]+)$/i);
  if (!match) return null;
  try {
    const verified = await verifySupabaseAccessToken(match[1], supabaseUrl);
    return { ...verified, accessToken: match[1] };
  } catch {
    return null;
  }
}
