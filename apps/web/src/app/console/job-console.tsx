"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./console.module.css";

type Recipe = {
  recipeId: string;
  name: string;
  maxRuntimeSeconds: number;
  minVramBytes: number;
};
type Job = {
  jobId: string;
  recipeId: string;
  state: string;
  createdAt: string;
  updatedAt: string;
  stopRequested: boolean;
};
type Snapshot = { recipes: Recipe[]; jobs: Job[] };

const example = JSON.stringify(
  {
    schemaVersion: 1,
    features: [[1], [2], [3], [4]],
    targets: [2, 4, 6, 8],
    epochs: 20,
    learningRate: 0.01,
  },
  null,
  2,
);

export function JobConsole() {
  const [snapshot, setSnapshot] = useState<Snapshot>({ recipes: [], jobs: [] });
  const [recipeId, setRecipeId] = useState("");
  const [input, setInput] = useState(example);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const refresh = useCallback(async () => {
    try {
      const response = await fetch("/api/console", { cache: "no-store" });
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? "Session expired. Sign in again."
            : "Control plane unavailable.",
        );
      const next = (await response.json()) as Snapshot;
      setSnapshot(next);
      setRecipeId((current) => current || next.recipes[0]?.recipeId || "");
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Could not load jobs",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initial = window.setTimeout(() => {
      void refresh();
    }, 0);
    const timer = window.setInterval(() => {
      void refresh();
    }, 10000);
    return () => {
      window.clearTimeout(initial);
      window.clearInterval(timer);
    };
  }, [refresh]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");
    let parsed: unknown;
    try {
      parsed = JSON.parse(input);
    } catch {
      setMessage("Input must be valid JSON.");
      return;
    }
    setBusy(true);
    try {
      const response = await fetch("/api/console", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ recipeId, input: parsed }),
      });
      const result = (await response.json()) as {
        jobId?: string;
        error?: string;
      };
      if (!response.ok)
        throw new Error(
          result.jobId
            ? `${result.error} Job ID: ${result.jobId}`
            : result.error || "Submission failed",
        );
      setMessage(
        `Job ${result.jobId} queued. Waiting for an eligible provider.`,
      );
      await refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Submission failed");
    } finally {
      setBusy(false);
    }
  }

  async function cancel(jobId: string) {
    setBusy(true);
    try {
      const response = await fetch(`/api/console/jobs/${jobId}/cancel`, {
        method: "POST",
      });
      const result = (await response.json()) as { state?: string };
      setMessage(
        response.ok
          ? result.state === "CANCELED"
            ? `Job ${jobId} canceled.`
            : `Stop requested for job ${jobId}. Active work is fenced and will be reconciled.`
          : "Cancellation is unavailable for this job state.",
      );
      await refresh();
    } catch {
      setMessage("Could not contact the control plane to stop this job.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.grid}>
      <section className={styles.panel} aria-labelledby="new-job">
        <div className={styles.panelHead}>
          <span className={styles.technical}>01 / NEW WORKLOAD</span>
          <span className={styles.rule} />
        </div>
        <h2 id="new-job">Prepare a job</h2>
        <p className={styles.hint}>
          Approved code only. Your JSON is privately staged and verified before
          the scheduler sees this job.
        </p>
        <form onSubmit={submit}>
          <label htmlFor="recipe" className={styles.label}>
            Approved recipe
          </label>
          <select
            id="recipe"
            value={recipeId}
            onChange={(event) => setRecipeId(event.target.value)}
            disabled={!snapshot.recipes.length || busy}
          >
            {!snapshot.recipes.length && (
              <option value="">No approved recipe available</option>
            )}
            {snapshot.recipes.map((recipe) => (
              <option key={recipe.recipeId} value={recipe.recipeId}>
                {recipe.name}
              </option>
            ))}
          </select>
          <label htmlFor="input" className={styles.label}>
            Input / request.json
          </label>
          <textarea
            id="input"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            spellCheck={false}
            rows={11}
            maxLength={32768}
          />
          <button
            className={styles.primary}
            disabled={!recipeId || busy || loading}
          >
            {busy ? "Submitting…" : "Queue workload"}
            <span aria-hidden="true">↗</span>
          </button>
        </form>
        {!snapshot.recipes.length && !loading && (
          <p className={styles.caveat}>
            No operator-approved image is currently enabled. Submission stays
            disabled until the recipe passes its qualification gate.
          </p>
        )}
        {message && (
          <p className={styles.message} role="status">
            {message}
          </p>
        )}
      </section>
      <section className={styles.panel} aria-labelledby="job-list">
        <div className={styles.panelHead}>
          <span className={styles.technical}>02 / YOUR JOBS</span>
          <span className={styles.rule} />
        </div>
        <div className={styles.listHeading}>
          <h2 id="job-list">Activity</h2>
          <button
            type="button"
            className={styles.refresh}
            onClick={() => void refresh()}
          >
            Refresh ↻
          </button>
        </div>
        <p className={styles.hint}>
          Owner-scoped status from the control plane. Updates every 10 seconds.
        </p>
        {loading ? (
          <p className={styles.empty}>Loading jobs…</p>
        ) : snapshot.jobs.length === 0 ? (
          <div className={styles.empty}>
            No jobs yet. An approved recipe will appear when the operator makes
            it available.
          </div>
        ) : (
          <ul className={styles.jobs}>
            {snapshot.jobs.map((job) => (
              <li key={job.jobId}>
                <div className={styles.jobTop}>
                  <span className={styles.jobId}>{job.jobId.slice(0, 8)}</span>
                  <span className={styles.state}>
                    {job.state.replaceAll("_", " ")}
                  </span>
                </div>
                <p>
                  {snapshot.recipes.find(
                    (recipe) => recipe.recipeId === job.recipeId,
                  )?.name || "Approved recipe"}
                </p>
                <time dateTime={job.createdAt}>
                  {new Date(job.createdAt).toLocaleString()}
                </time>
                <div className={styles.jobActions}>
                  {job.stopRequested && <span>Stop requested</span>}
                  {job.state === "SUCCEEDED" && (
                    <a href={`/api/console/jobs/${job.jobId}/result`}>
                      Download verified artifact ↗
                    </a>
                  )}
                  {!job.stopRequested &&
                    [
                      "CREATED",
                      "QUEUED",
                      "RETRY_WAIT",
                      "LEASED",
                      "STARTING",
                      "RUNNING",
                      "UPLOADING_RESULTS",
                    ].includes(job.state) && (
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void cancel(job.jobId)}
                      >
                        Cancel job
                      </button>
                    )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
