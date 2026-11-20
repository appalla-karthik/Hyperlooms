/** A small woven mark: independent threads meet at the centre. */
export function LoomMark({ className = "" }: { className?: string }) {
  return (
    <svg
      className={className}
      width="32"
      height="32"
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M5 2v28M12 2v28M20 2v28M27 2v28M2 9h28M2 16h28M2 23h28"
        stroke="currentColor"
        strokeWidth="2.4"
      />
      <path
        d="M12 9h8v14h-8z"
        fill="var(--background)"
        stroke="currentColor"
        strokeWidth="2.4"
      />
    </svg>
  );
}
