import { NextResponse } from "next/server";
import { clearSession, requireSameOrigin, settings } from "@/lib/console-auth";

export async function POST(request: Request) {
  if (!requireSameOrigin(request))
    return new Response("Forbidden", { status: 403 });
  await clearSession();
  return NextResponse.redirect(new URL("/console", settings().origin), {
    status: 303,
  });
}
