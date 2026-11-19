type PhaseBadgeProps = {
  phase: "FOUNDATION";
};

export function PhaseBadge({ phase }: PhaseBadgeProps) {
  return (
    <span className="rounded-full border border-[var(--border)] bg-[var(--surface)] px-4 py-2 text-xs font-semibold tracking-[0.14em] text-[var(--accent)]">
      {phase}
    </span>
  );
}
