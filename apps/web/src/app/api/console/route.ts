import { createHash, randomUUID } from "node:crypto";
import { apiFetch, requireSameOrigin } from "@/lib/console-auth";

export const runtime = "nodejs";

function upstreamError(status: number) {
  return Response.json(
    { error: `Control plane rejected the request (${status})` },
    { status },
  );
}

export async function GET() {
  try {
    const [jobs, recipes] = await Promise.all([
      apiFetch("/api/v1/jobs"),
      apiFetch("/api/v1/jobs/recipes"),
    ]);
    if (!jobs || !recipes)
      return Response.json({ error: "Sign in required" }, { status: 401 });
    if (!jobs.ok) return upstreamError(jobs.status);
    if (!recipes.ok) return upstreamError(recipes.status);
    return Response.json(
      { jobs: await jobs.json(), recipes: await recipes.json() },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch {
    return Response.json(
      { error: "Control plane unavailable" },
      { status: 503 },
    );
  }
}

export async function POST(request: Request) {
  if (!requireSameOrigin(request))
    return Response.json({ error: "Forbidden" }, { status: 403 });
  if (
    !request.headers.get("content-type")?.startsWith("application/json") ||
    Number(request.headers.get("content-length") ?? 0) > 65536
  ) {
    return Response.json(
      { error: "Expected JSON under 64 KiB" },
      { status: 413 },
    );
  }
  try {
    const body = await request.text();
    if (body.length > 65536)
      return Response.json({ error: "Request too large" }, { status: 413 });
    const payload = JSON.parse(body) as { recipeId?: unknown; input?: unknown };
    if (
      typeof payload.recipeId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f-]{27}$/.test(payload.recipeId) ||
      typeof payload.input !== "object" ||
      payload.input === null ||
      Array.isArray(payload.input)
    ) {
      return Response.json(
        { error: "Recipe and input JSON are required" },
        { status: 400 },
      );
    }
    const input = Buffer.from(JSON.stringify(payload.input));
    if (input.length > 32768)
      return Response.json(
        { error: "Input exceeds console limit" },
        { status: 413 },
      );
    const hash = (bytes: Buffer) =>
      createHash("sha256").update(bytes).digest("hex");
    const manifest = Buffer.from(
      JSON.stringify({
        files: [
          {
            path: "request.json",
            sha256: hash(input),
            sizeBytes: input.length,
          },
        ],
        schemaVersion: 1,
      }),
    );
    const created = await apiFetch("/api/v1/jobs", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": randomUUID(),
      },
      body: JSON.stringify({
        recipeId: payload.recipeId,
        inputManifestSha256: hash(manifest),
      }),
    });
    if (!created)
      return Response.json({ error: "Sign in required" }, { status: 401 });
    if (!created.ok) return upstreamError(created.status);
    const job = (await created.json()) as { jobId: string };
    if (!/^[0-9a-f-]{36}$/.test(job.jobId)) throw new Error("Invalid job ID");
    const base = `/api/v1/jobs/${job.jobId}`;
    const stagedManifest = await apiFetch(`${base}/inputs/manifest`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: manifest,
    });
    const stagedFile = stagedManifest?.ok
      ? await apiFetch(`${base}/inputs/files?path=request.json`, {
          method: "PUT",
          headers: { "Content-Type": "application/octet-stream" },
          body: input,
        })
      : null;
    const queued = stagedFile?.ok
      ? await apiFetch(`${base}/queue`, { method: "POST" })
      : null;
    if (!queued?.ok) {
      return Response.json(
        {
          jobId: job.jobId,
          state: "CREATED",
          error:
            "Job was created but input staging or queueing failed; inspect it before retrying",
        },
        { status: 409 },
      );
    }
    return Response.json(
      { jobId: job.jobId, state: "QUEUED" },
      { status: 201 },
    );
  } catch {
    return Response.json(
      { error: "Could not submit the job" },
      { status: 400 },
    );
  }
}
