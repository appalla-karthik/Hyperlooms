"use client";
import { useRef, useState, type KeyboardEvent } from "react";
const experiences = [
  {
    id: "builders",
    tab: "For builders",
    label: "LESS LIMITATION. MORE CREATION.",
    title: "Bring the idea.",
    emphasis: "Find the compute.",
    description:
      "A planned path from your AI workload to a compatible community GPU. Without making a new workstation your first investment.",
    items: [
      "Submit a supported AI workload",
      "Match with a compatible provider",
      "Get your output artifacts back",
    ],
    diagramLabel: "THE BUILDER’S JOURNEY",
    start: "Your workload",
    startDetail: "Approved AI image",
    middle: "The network",
    middleDetail: "Compatibility & scheduling",
    end: "Provider GPU",
    endDetail: "Restricted execution",
    result: "Output artifacts",
    resultDetail: "Returned to the builder",
    caption: "You bring the work. The network coordinates the rest.",
  },
  {
    id: "providers",
    tab: "For providers",
    label: "IDLE CAPACITY. NEW POSSIBILITIES.",
    title: "Your spare power.",
    emphasis: "Someone’s next step.",
    description:
      "Help make ambitious work possible by contributing compatible GPU capacity. The design starts with visibility and control for the person sharing it.",
    items: [
      "Connect a compatible provider machine",
      "Offer capacity when you choose",
      "Track assigned work and its outcome",
    ],
    diagramLabel: "THE PROVIDER’S JOURNEY",
    start: "Your GPU",
    startDetail: "Compatible capacity",
    middle: "Provider agent",
    middleDetail: "Authenticated connection",
    end: "The network",
    endDetail: "Workload assignment",
    result: "Job outcome",
    resultDetail: "Execution & accounting evidence",
    caption: "Independent hardware. A coordinated contribution.",
  },
] as const;

export function NetworkExperience() {
  const [active, setActive] = useState(0);
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const experience = experiences[active]!;
  function handleKey(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = index;
    if (event.key === "ArrowRight" || event.key === "ArrowLeft")
      next = 1 - index;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = experiences.length - 1;
    else return;
    event.preventDefault();
    setActive(next);
    buttons.current[next]?.focus();
  }
  return (
    <div className="experience">
      <div className="experience-topline">
        <div
          className="experience-tabs"
          role="tablist"
          aria-label="Explore the network by role"
        >
          {experiences.map((item, index) => (
            <button
              key={item.id}
              id={`tab-${item.id}`}
              role="tab"
              aria-selected={active === index}
              aria-controls={`panel-${item.id}`}
              tabIndex={active === index ? 0 : -1}
              ref={(element) => {
                buttons.current[index] = element;
              }}
              onClick={() => setActive(index)}
              onKeyDown={(event) => handleKey(event, index)}
            >
              {item.tab}
            </button>
          ))}
        </div>
        <span className="preview-label">PLANNED EXPERIENCE</span>
      </div>
      {experiences.map((item, index) => (
        <div
          key={item.id}
          id={`panel-${item.id}`}
          role="tabpanel"
          aria-labelledby={`tab-${item.id}`}
          hidden={active !== index}
          tabIndex={0}
          className="experience-panel"
        >
          {active === index && (
            <>
              <div className="experience-copy">
                <p className="eyebrow">{experience.label}</p>
                <h3>
                  {experience.title}
                  <br />
                  <em>{experience.emphasis}</em>
                </h3>
                <p className="experience-description">
                  {experience.description}
                </p>
                <ul>
                  {experience.items.map((line) => (
                    <li key={line}>
                      <span aria-hidden="true">✓</span>
                      {line}
                    </li>
                  ))}
                </ul>
                <a className="text-link" href="#progress">
                  See what’s being built <span aria-hidden="true">↗</span>
                </a>
              </div>
              <div className="journey">
                <div className="journey-topline">
                  <span className="micro-label">{experience.diagramLabel}</span>
                  <span className="diagram-key">
                    <i /> Concept
                  </span>
                </div>
                <div className="journey-flow">
                  <div className="journey-node">
                    <span className="diagram-icon" aria-hidden="true">
                      {active === 0 ? <CodeIcon /> : <ChipIcon />}
                    </span>
                    <strong>{experience.start}</strong>
                    <span>{experience.startDetail}</span>
                  </div>
                  <div className="journey-connector" aria-hidden="true">
                    <span />
                  </div>
                  <div className="journey-node central-node">
                    <span className="diagram-icon" aria-hidden="true">
                      <NetworkIcon />
                    </span>
                    <strong>{experience.middle}</strong>
                    <span>{experience.middleDetail}</span>
                  </div>
                  <div className="journey-connector" aria-hidden="true">
                    <span />
                  </div>
                  <div className="journey-node">
                    <span className="diagram-icon" aria-hidden="true">
                      {active === 0 ? <ChipIcon /> : <NetworkIcon />}
                    </span>
                    <strong>{experience.end}</strong>
                    <span>{experience.endDetail}</span>
                  </div>
                </div>
                <div className="result-connector" aria-hidden="true" />
                <div className="journey-result">
                  <span className="result-icon" aria-hidden="true">
                    ✓
                  </span>
                  <div>
                    <strong>{experience.result}</strong>
                    <span>{experience.resultDetail}</span>
                  </div>
                  <span className="result-file" aria-hidden="true">
                    {active === 0 ? ". / outputs" : ". / evidence"}
                  </span>
                </div>
                <p className="journey-caption">{experience.caption}</p>
              </div>
            </>
          )}
        </div>
      ))}
    </div>
  );
}
function ChipIcon() {
  return (
    <svg
      width="26"
      height="26"
      viewBox="0 0 26 26"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
    >
      <rect x="6" y="6" width="14" height="14" rx="2" />
      <rect x="10" y="10" width="6" height="6" rx="1" />
      <path d="M9 2v4m8-4v4M9 20v4m8-4v4M2 9h4m-4 8h4m14-8h4m-4 8h4" />
    </svg>
  );
}
function CodeIcon() {
  return (
    <svg
      width="26"
      height="26"
      viewBox="0 0 26 26"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
    >
      <rect x="3" y="4" width="20" height="18" rx="2" />
      <path d="m8 10 3 3-3 3m6 0h5" />
    </svg>
  );
}
function NetworkIcon() {
  return (
    <svg
      width="26"
      height="26"
      viewBox="0 0 26 26"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
    >
      <path d="M7 7h12v12H7zm0 0 12 12m0-12L7 19" />
      <circle cx="7" cy="7" r="3" fill="var(--diagram-node)" />
      <circle cx="19" cy="7" r="3" fill="var(--diagram-node)" />
      <circle cx="7" cy="19" r="3" fill="var(--diagram-node)" />
      <circle cx="19" cy="19" r="3" fill="var(--diagram-node)" />
    </svg>
  );
}
