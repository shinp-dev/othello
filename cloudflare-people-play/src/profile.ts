export interface CanonicalPeoplePlayProfile {
  displayName: string;
}

export type ProfileResolutionErrorCode = "PROFILE_REQUIRED" | "PROFILE_UNAVAILABLE";

export class ProfileResolutionError extends Error {
  constructor(readonly code: ProfileResolutionErrorCode) {
    super(code);
    this.name = "ProfileResolutionError";
  }
}

export interface ProfileLookupIdentity {
  userId: string;
  accessToken: string;
}

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

async function fetchRows(
  url: URL,
  identity: ProfileLookupIdentity,
  anonKey: string,
  fetcher: Fetcher,
): Promise<unknown[]> {
  let response: Response;
  try {
    response = await fetcher(url, {
      method: "GET",
      headers: {
        apikey: anonKey,
        authorization: `Bearer ${identity.accessToken}`,
        accept: "application/json",
      },
    });
  } catch {
    throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  }
  if (!response.ok) throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  try {
    const value: unknown = await response.json();
    if (!Array.isArray(value)) throw new Error("Expected array");
    return value;
  } catch {
    throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  }
}

export async function resolveCanonicalPeoplePlayProfile(
  identity: ProfileLookupIdentity,
  supabaseUrl: string,
  anonKey: string,
  fetcher: Fetcher = fetch,
): Promise<CanonicalPeoplePlayProfile> {
  const baseUrl = supabaseUrl.replace(/\/+$/, "");
  const profileUrl = new URL(`${baseUrl}/rest/v1/play_profiles`);
  profileUrl.searchParams.set("select", "display_name_id");
  profileUrl.searchParams.set("user_id", `eq.${identity.userId}`);
  profileUrl.searchParams.set("limit", "2");
  const profiles = await fetchRows(profileUrl, identity, anonKey, fetcher);
  if (profiles.length === 0) throw new ProfileResolutionError("PROFILE_REQUIRED");
  if (profiles.length !== 1 || !isRecord(profiles[0])) {
    throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  }
  const displayNameId = profiles[0].display_name_id;
  if ((typeof displayNameId !== "number" && typeof displayNameId !== "string") || String(displayNameId).trim() === "") {
    throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  }

  const displayNameUrl = new URL(`${baseUrl}/rest/v1/play_display_names`);
  displayNameUrl.searchParams.set("select", "id,display_name");
  displayNameUrl.searchParams.set("id", `eq.${String(displayNameId)}`);
  displayNameUrl.searchParams.set("limit", "2");
  const names = await fetchRows(displayNameUrl, identity, anonKey, fetcher);
  if (names.length !== 1 || !isRecord(names[0])) throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  if (String(names[0].id) !== String(displayNameId)) throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  const displayName = names[0].display_name;
  if (typeof displayName !== "string" || displayName.trim().length === 0) {
    throw new ProfileResolutionError("PROFILE_UNAVAILABLE");
  }
  return { displayName };
}
