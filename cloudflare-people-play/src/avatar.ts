import { AVATAR_IDS, type AvatarId } from "./registry-core.js";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function deriveAvatarId(userId: string): AvatarId {
  if (!UUID_PATTERN.test(userId)) throw new Error("Invalid authenticated user ID");
  const prefix = userId.replaceAll("-", "").slice(0, 8);
  const value = Number.parseInt(prefix, 16);
  return AVATAR_IDS[value % AVATAR_IDS.length];
}
