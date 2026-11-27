import { apiFetch, requireSameOrigin } from "@/lib/console-auth";

export async function POST(
  request: Request,
  context: { params: Promise<{ jobId: string }> },
) {
  if (!requireSameOrigin(request))
    return new Response("Forbidden", { status: 403 });
  const { jobId } = await context.params;
  if (!/^[0-9a-f-]{36}$/.test(jobId))
    return new Response("Invalid job", { status: 400 });
  try {
    const response = await apiFetch(`/api/v1/jobs/${jobId}/cancel`, {
      method: "POST",
    });
    if (!response) return new Response("Sign in required", { status: 401 });
    return Response.json(
      response.ok
        ? await response.json()
        : { error: "Cancellation unavailable" },
      { status: response.status },
    );
  } catch {
    return new Response("Control plane unavailable", { status: 503 });
  }
}
