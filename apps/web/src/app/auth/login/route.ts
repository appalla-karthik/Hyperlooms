import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import * as oidc from "openid-client";
import {
  oidcConfig,
  seal,
  settings,
  transactionCookie,
} from "@/lib/console-auth";

export const runtime = "nodejs";

export async function GET() {
  try {
    const config = settings();
    const verifier = oidc.randomPKCECodeVerifier();
    const state = oidc.randomState();
    const nonce = oidc.randomNonce();
    const challenge = await oidc.calculatePKCECodeChallenge(verifier);
    const authorization = oidc.buildAuthorizationUrl(await oidcConfig(), {
      redirect_uri: `${config.origin}/auth/callback`,
      scope: "openid jobs:read jobs:write",
      code_challenge: challenge,
      code_challenge_method: "S256",
      state,
      nonce,
    });
    (await cookies()).set(
      transactionCookie,
      seal({ verifier, state, nonce, expiresAt: Date.now() + 300000 }),
      {
        httpOnly: true,
        secure: new URL(config.origin).protocol === "https:",
        sameSite: "lax",
        path: "/auth",
        maxAge: 300,
      },
    );
    return NextResponse.redirect(authorization);
  } catch {
    return NextResponse.json(
      { error: "Identity service is unavailable or not configured" },
      { status: 503 },
    );
  }
}
