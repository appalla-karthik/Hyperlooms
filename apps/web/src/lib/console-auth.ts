import "server-only";

import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { cookies } from "next/headers";
import * as oidc from "openid-client";

const sessionCookie = "hl_session";
export const transactionCookie = "hl_oidc";

type Envelope = { accessToken: string; expiresAt: number };
export type LoginTransaction = {
  verifier: string;
  state: string;
  nonce: string;
  expiresAt: number;
};

export function settings() {
  const origin = process.env.HYPERLOOMS_WEB_ORIGIN;
  const issuer = process.env.HYPERLOOMS_WEB_OIDC_ISSUER;
  const clientId = process.env.HYPERLOOMS_WEB_OIDC_CLIENT_ID;
  const api = process.env.HYPERLOOMS_WEB_API_ORIGIN;
  if (!origin || !issuer || !clientId || !api) {
    throw new Error("Requester console is not configured");
  }
  for (const address of [origin, issuer, api]) {
    const url = new URL(address);
    const local = ["localhost", "127.0.0.1"].includes(url.hostname);
    if (
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      (url.protocol !== "https:" &&
        !(
          url.protocol === "http:" &&
          local &&
          process.env.HYPERLOOMS_WEB_ALLOW_INSECURE_LOCAL === "true"
        ))
    ) {
      throw new Error(
        "Console endpoints must use HTTPS, except explicit loopback development",
      );
    }
  }
  return { origin, issuer, clientId, api };
}

function key(): Buffer {
  const encoded = process.env.HYPERLOOMS_WEB_SESSION_KEY;
  const decoded = encoded ? Buffer.from(encoded, "base64") : Buffer.alloc(0);
  if (decoded.length !== 32)
    throw new Error(
      "HYPERLOOMS_WEB_SESSION_KEY must be 32 random base64 bytes",
    );
  return decoded;
}

export function seal(value: object): string {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key(), iv);
  const body = Buffer.concat([
    cipher.update(JSON.stringify(value), "utf8"),
    cipher.final(),
  ]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString("base64url");
}

export function unseal<T>(encoded: string | undefined): T | null {
  if (!encoded || encoded.length > 6000) return null;
  try {
    const raw = Buffer.from(encoded, "base64url");
    if (raw.length < 29) return null;
    const decipher = createDecipheriv(
      "aes-256-gcm",
      key(),
      raw.subarray(0, 12),
    );
    decipher.setAuthTag(raw.subarray(12, 28));
    return JSON.parse(
      Buffer.concat([
        decipher.update(raw.subarray(28)),
        decipher.final(),
      ]).toString(),
    ) as T;
  } catch {
    return null;
  }
}

export async function oidcConfig() {
  const config = settings();
  const issuer = new URL(config.issuer);
  return oidc.discovery(
    issuer,
    config.clientId,
    undefined,
    oidc.None(),
    issuer.protocol === "http:"
      ? { execute: [oidc.allowInsecureRequests], timeout: 10 }
      : { timeout: 10 },
  );
}

export async function sessionToken(): Promise<string | null> {
  const stored = unseal<Envelope>((await cookies()).get(sessionCookie)?.value);
  if (
    !stored ||
    typeof stored.accessToken !== "string" ||
    typeof stored.expiresAt !== "number" ||
    stored.expiresAt <= Date.now() + 5000
  )
    return null;
  return stored.accessToken;
}

export async function setSession(accessToken: string, expiresIn: number) {
  const age = Math.min(Math.max(Math.floor(expiresIn) - 15, 0), 3600);
  if (!age) throw new Error("Identity provider returned an expired token");
  (await cookies()).set(
    sessionCookie,
    seal({ accessToken, expiresAt: Date.now() + age * 1000 }),
    {
      httpOnly: true,
      secure: new URL(settings().origin).protocol === "https:",
      sameSite: "lax",
      path: "/",
      maxAge: age,
    },
  );
}

export async function clearSession() {
  (await cookies()).delete(sessionCookie);
}

export function requireSameOrigin(request: Request): boolean {
  try {
    return request.headers.get("origin") === settings().origin;
  } catch {
    return false;
  }
}

export async function apiFetch(path: string, init: RequestInit = {}) {
  const token = await sessionToken();
  if (!token) return null;
  const { api } = settings();
  return fetch(new URL(path, api), {
    ...init,
    cache: "no-store",
    signal: AbortSignal.timeout(15000),
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
}
