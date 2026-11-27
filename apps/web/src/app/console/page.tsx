import Link from "next/link";
import { sessionToken, settings } from "@/lib/console-auth";
import { JobConsole } from "./job-console";
import styles from "./console.module.css";

export const dynamic = "force-dynamic";

export default async function ConsolePage() {
  let configured = true;
  try {
    settings();
  } catch {
    configured = false;
  }
  const signedIn = configured && !!(await sessionToken());
  return (
    <main className={styles.shell}>
      <header className={styles.header}>
        <Link href="/" className={styles.brand}>
          Hyperlooms<span>.</span>
        </Link>
        <div className={styles.headerRight}>
          <span className={styles.technical}>REQUESTER CONSOLE / V1</span>
          {signedIn ? (
            <form action="/auth/logout" method="post">
              <button className={styles.quiet}>Sign out</button>
            </form>
          ) : (
            <Link href="/" className={styles.quiet}>
              Back to site
            </Link>
          )}
        </div>
      </header>
      <div className={styles.content}>
        <div className={styles.intro}>
          <div className={styles.kicker}>
            <span className={styles.dot} /> CONTROLLED PREVIEW
          </div>
          <h1>
            Submit work.
            <br />
            <em>Follow every state.</em>
          </h1>
          <p>
            The first Hyperlooms workflow is a bounded AI training recipe on one
            verified provider GPU. Jobs only run when an approved recipe and a
            qualified provider are available.
          </p>
        </div>
        {!configured ? (
          <div className={styles.notice} role="status">
            Console configuration is incomplete. Set the server-only OIDC, API,
            origin and session-key variables before using it.
          </div>
        ) : !signedIn ? (
          <div className={styles.loginCard}>
            <span className={styles.technical}>IDENTITY REQUIRED</span>
            <h2>Start with a verified account.</h2>
            <p>
              Sign in through the configured identity provider to submit and
              inspect only your jobs. Public signup is not open.
            </p>
            <a className={styles.primary} href="/auth/login">
              Sign in to console <span aria-hidden="true">↗</span>
            </a>
          </div>
        ) : (
          <JobConsole />
        )}
      </div>
    </main>
  );
}
