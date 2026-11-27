import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import * as oidc from "openid-client";
import {
  LoginTransaction,
  oidcConfig,
  setSession,
  settings,
  transactionCookie,
  unseal,
} from "@/lib/console-auth";

export const runtime = "nodejs";

export async function GET(request: Request) {
  const saved = unseal<LoginTransaction>(
    (await cookies()).get(transactionCookie)?.value,
  );
  (await cookies()).delete(transactionCookie);
  if (
    !saved ||
    !saved.verifier ||
    !saved.state ||
    !saved.nonce ||
    saved.expiresAt <= Date.now()
  ) {
    return NextResponse.json(
      { error: "Login transaction expired" },
      { status: 400 },
    );
  }
  try {
    const config = settings();
    const callback = new URL(request.url);
    if (
      callback.origin !== config.origin ||
      callback.pathname !== "/auth/callback"
    ) {
      return NextResponse.json(
        { error: "Invalid callback origin" },
        { status: 400 },
      );
    }
    const tokens = await oidc.authorizationCodeGrant(
      await oidcConfig(),
      callback,
      {
        pkceCodeVerifier: saved.verifier,
        expectedState: saved.state,
        expectedNonce: saved.nonce,
        idTokenExpected: true,
      },
    );
    if (!tokens.claims()?.sub || !tokens.access_token || !tokens.expires_in) {
      throw new Error("Identity token is incomplete");
    }
    await setSession(tokens.access_token, tokens.expires_in);
    return NextResponse.redirect(new URL("/console", config.origin));
  } catch {
    return NextResponse.json(
      { error: "Login could not be completed" },
      { status: 401 },
    );
  }
}
