import { apiFetch } from "@/lib/console-auth";

export async function GET(
  _request: Request,
  context: { params: Promise<{ jobId: string }> },
) {
  const { jobId } = await context.params;
  if (!/^[0-9a-f-]{36}$/.test(jobId))
    return new Response("Invalid job", { status: 400 });
  try {
    const response = await apiFetch(`/api/v1/jobs/${jobId}/result`);
    if (!response) return new Response("Sign in required", { status: 401 });
    if (!response.ok)
      return new Response("Result unavailable", { status: response.status });
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength > 1024 * 1024)
      return new Response("Result too large", { status: 502 });
    return new Response(bytes, {
      headers: {
        "Content-Type": "application/json",
        "Content-Disposition": `attachment; filename="hyperlooms-${jobId}-result.json"`,
        "Cache-Control": "no-store",
      },
    });
  } catch {
    return new Response("Control plane unavailable", { status: 503 });
  }
}
