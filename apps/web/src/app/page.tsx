import { PhaseBadge } from "@/components/phase-badge";
import { foundationCapabilities } from "@/lib/foundation-capabilities";

const futureFlow = [
  "Submit an approved AI workload",
  "Match it to a compatible provider",
  "Lease one GPU through the control plane",
  "Execute inside a restricted sandbox",
  "Return verified artifacts and accounting evidence",
];

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-6 py-10 sm:px-10 lg:px-12">
      <header className="flex items-center justify-between border-b border-[var(--border)] pb-6">
        <div>
          <p className="text-xs font-semibold tracking-[0.3em] text-[var(--accent)]">
            HYPER_LOOMS
          </p>
          <p className="mt-2 text-sm text-[var(--muted)]">
            Community-owned GPU compute
          </p>
        </div>
        <PhaseBadge phase="FOUNDATION" />
      </header>

      <section className="grid flex-1 gap-12 py-16 lg:grid-cols-[1.15fr_0.85fr] lg:items-center">
        <div>
          <p className="mb-5 text-sm font-medium text-[var(--accent)]">
            Engineering foundation
          </p>
          <h1 className="max-w-3xl text-5xl font-semibold leading-[1.05] tracking-[-0.04em] sm:text-6xl">
            Compute belongs to everyone.
          </h1>
          <p className="mt-7 max-w-2xl text-lg leading-8 text-[var(--muted)]">
            HYPER_LOOMS is building a trusted network where communities
            contribute compatible GPU capacity and run supported AI workloads
            without owning equivalent hardware.
          </p>

          <div className="mt-10 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
            <div className="flex items-center justify-between gap-4">
              <h2 className="text-base font-semibold">
                Current repository status
              </h2>
              <span className="rounded-full bg-amber-300/10 px-3 py-1 text-xs font-medium text-amber-200">
                No remote execution yet
              </span>
            </div>
            <ul className="mt-5 grid gap-3 text-sm text-[var(--muted)] sm:grid-cols-2">
              {foundationCapabilities.map((capability) => (
                <li className="flex items-start gap-3" key={capability}>
                  <span className="mt-1 text-[var(--accent)]">✓</span>
                  <span>{capability}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>

        <aside className="rounded-3xl border border-[var(--border)] bg-[var(--surface)] p-7 shadow-2xl shadow-black/20">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[var(--muted)]">
            First vertical slice
          </p>
          <ol className="mt-7 space-y-5">
            {futureFlow.map((step, index) => (
              <li className="flex gap-4" key={step}>
                <span className="flex size-8 shrink-0 items-center justify-center rounded-full border border-[var(--border)] bg-[var(--surface-strong)] text-sm text-[var(--accent)]">
                  {index + 1}
                </span>
                <p className="pt-1 text-sm leading-6 text-[var(--muted)]">
                  {step}
                </p>
              </li>
            ))}
          </ol>
          <p className="mt-8 border-t border-[var(--border)] pt-6 text-xs leading-5 text-[var(--muted)]">
            Planned behavior is shown as planned. The dashboard will expose real
            network data only after the underlying control-plane behavior
            exists.
          </p>
        </aside>
      </section>
    </main>
  );
}
