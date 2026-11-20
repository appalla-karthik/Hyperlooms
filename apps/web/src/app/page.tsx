import { NetworkExperience } from "@/components/network-experience";
import { SiteHeader } from "@/components/site-header";
import { LoomMark } from "@/components/loom-artwork";
import { foundationCapabilities } from "@/lib/foundation-capabilities";

const repository = "https://github.com/appalla-karthik/Hyperlooms";
const questions = [
  {
    question: "Can I use or contribute a GPU today?",
    answer:
      "Not yet. Hyperlooms is in its foundation stage. The application scaffolding, local infrastructure and engineering checks are in place, but provider onboarding and remote workload execution are not available. Follow the repository for implementation progress.",
  },
  {
    question: "What are you building for first?",
    answer:
      "Our first target is a supported AI workload running on one compatible provider GPU, with a clear job lifecycle and returned output artifacts. Multi-GPU training, arbitrary workloads and cloud gaming are not part of the first release.",
  },
  {
    question: "How will sharing work safely?",
    answer:
      "The planned design uses authenticated provider connections, approved workload images, restricted execution and expiring job leases. These controls still need to be implemented and validated. Containers alone are not a complete security boundary for untrusted code.",
  },
  {
    question: "Is the network fully decentralized?",
    answer:
      "The vision is community-supplied compute. The initial architecture uses a central control plane to coordinate distributed providers, schedule work and record outcomes. It is not a blockchain network or a fully decentralized scheduler.",
  },
];

export default function Home() {
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <SiteHeader />
      <a
        className="development-label floating-development-label"
        href="#progress"
      >
        <span className="status-dot" /> BUILDING IN THE OPEN
        <span className="label-divider" /> FOUNDATION STAGE
      </a>
      <main id="main">
        <section
          className="hero-vista"
          id="hero-vista"
          aria-labelledby="hero-title"
        >
          <div className="hero-vista-copy section-shell">
            <h1 id="hero-title">
              Big ideas.
              <br />
              <em>Shared power.</em>
            </h1>
            <p className="hero-description">
              Your ambition shouldn’t be limited by your hardware. We’re
              building a community-powered GPU network for people ready to
              create.
            </p>
            <div className="hero-actions">
              <a className="button button-primary" href="#network">
                Explore the vision <span aria-hidden="true">↗</span>
              </a>
              <a
                className="hero-secondary"
                href={repository}
                target="_blank"
                rel="noopener noreferrer"
              >
                Follow the build <span aria-hidden="true">↗</span>
                <span className="sr-only"> on GitHub (opens in a new tab)</span>
              </a>
            </div>
            <p className="hero-note">
              A shared future for compute. Starting with AI.
            </p>
          </div>
          <a
            className="hero-scroll"
            href="#network"
            aria-label="Meet the network"
          >
            <span aria-hidden="true">↓</span> MEET THE NETWORK
          </a>
        </section>
        <div
          className="principles-strip section-shell"
          aria-label="Our principles"
        >
          <p>
            <span className="principle-icon" aria-hidden="true">
              ◎
            </span>{" "}
            Community-powered <span>By people, for people.</span>
          </p>
          <p>
            <span className="principle-icon" aria-hidden="true">
              ⌘
            </span>{" "}
            AI-first <span>A focused starting point.</span>
          </p>
          <p>
            <span className="principle-icon" aria-hidden="true">
              ↗
            </span>{" "}
            Built in the open <span>Progress you can follow.</span>
          </p>
        </div>
        <section
          className="network-section section-shell"
          id="network"
          aria-labelledby="network-title"
        >
          <div className="section-intro">
            <p className="eyebrow">ONE NETWORK. TWO SIDES.</p>
            <div className="section-heading-row">
              <h2 id="network-title">
                Great work takes
                <br />
                <em>a little connection.</em>
              </h2>
              <p>
                A GPU sitting idle somewhere could be the beginning of something
                extraordinary somewhere else.
              </p>
            </div>
          </div>
          <NetworkExperience />
        </section>
        <section
          className="belief-section section-shell"
          id="approach"
          aria-labelledby="approach-title"
        >
          <div className="belief-heading">
            <p className="eyebrow">THE WAY WE’RE BUILDING</p>
            <h2 id="approach-title">
              Open ambition.
              <br />
              <em>Grounded engineering.</em>
            </h2>
          </div>
          <div className="belief-grid">
            <article>
              <span className="belief-number">01 / ACCESS</span>
              <h3>
                More possibility.
                <br />
                Less hardware.
              </h3>
              <p>
                The idea is simple: help builders work with compatible GPU
                capacity beyond the machine on their desk.
              </p>
            </article>
            <article>
              <span className="belief-number">02 / TRUST</span>
              <h3>
                Sharing power
                <br />
                takes responsibility.
              </h3>
              <p>
                Provider control, restricted workloads and traceable outcomes
                are design requirements—not details to add later.
              </p>
            </article>
            <article>
              <span className="belief-number">03 / FOCUS</span>
              <h3>
                One workload.
                <br />
                Done properly.
              </h3>
              <p>
                We’re starting with a single-GPU AI workflow. A small,
                verifiable first step towards a much bigger network.
              </p>
            </article>
          </div>
        </section>
        <section
          className="progress-section"
          id="progress"
          aria-labelledby="progress-title"
        >
          <div className="section-shell progress-inner">
            <div className="progress-copy">
              <p className="eyebrow">FROM IDEA TO INFRASTRUCTURE</p>
              <h2 id="progress-title">
                Not a promise of scale.
                <br />
                <em>A foundation for it.</em>
              </h2>
              <p>
                Good infrastructure earns trust one working piece at a time.
                Here’s an honest look at where we stand.
              </p>
              <a
                className="text-link"
                href={repository}
                target="_blank"
                rel="noopener noreferrer"
              >
                See the source on GitHub <span aria-hidden="true">↗</span>
                <span className="sr-only"> (opens in a new tab)</span>
              </a>
              <div className="progress-note">
                <span className="status-dot" />
                <span>No live network or remote execution yet.</span>
              </div>
            </div>
            <div className="build-ledger">
              <div className="ledger-heading">
                <span className="micro-label">CURRENT MILESTONE</span>
                <span className="stage-tag">Phase 0</span>
              </div>
              <h3>Engineering foundation</h3>
              <ul>
                {foundationCapabilities.map((capability) => (
                  <li key={capability}>
                    <span className="check-mark" aria-hidden="true">
                      ✓
                    </span>
                    {capability}
                  </li>
                ))}
              </ul>
              <div className="next-milestone">
                <span className="micro-label">NEXT · NOT IMPLEMENTED</span>
                <p>
                  Connect a provider. Run a workload.
                  <br />
                  Return a verifiable result.
                </p>
              </div>
            </div>
          </div>
        </section>
        <section
          className="faq-section section-shell"
          aria-labelledby="faq-title"
        >
          <div>
            <p className="eyebrow">A FEW THINGS TO KNOW</p>
            <h2 id="faq-title">
              Good questions.
              <br />
              <em>Straight answers.</em>
            </h2>
          </div>
          <div className="faq-list">
            {questions.map(({ question, answer }) => (
              <details key={question}>
                <summary>
                  {question}
                  <span className="faq-toggle" aria-hidden="true" />
                </summary>
                <p>{answer}</p>
              </details>
            ))}
          </div>
        </section>
        <section
          className="closing-section section-shell"
          aria-labelledby="closing-title"
        >
          <LoomMark className="closing-mark" />
          <p className="eyebrow">THE FUTURE IS A SHARED EFFORT.</p>
          <h2 id="closing-title">
            Let’s make room
            <br />
            for <em>what’s next.</em>
          </h2>
          <a
            className="button button-primary"
            href={repository}
            target="_blank"
            rel="noopener noreferrer"
          >
            Build with us on GitHub
            <span className="sr-only"> (opens in a new tab)</span>
          </a>
          <p className="closing-note">
            Follow the progress. Explore the code. Be part of the conversation.
          </p>
        </section>
      </main>
      <footer className="site-footer section-shell">
        <a className="brand" href="#main" aria-label="Hyperlooms, back to top">
          Hyperlooms
        </a>
        <p>Compute is better, together.</p>
        <div>
          <a
            href={`${repository}/blob/main/SECURITY.md`}
            target="_blank"
            rel="noopener noreferrer"
          >
            Security<span className="sr-only"> (opens in a new tab)</span>
          </a>
          <a href={repository} target="_blank" rel="noopener noreferrer">
            GitHub<span className="sr-only"> (opens in a new tab)</span>
          </a>
          <span>© {new Date().getFullYear()} Hyperlooms</span>
        </div>
      </footer>
    </>
  );
}
