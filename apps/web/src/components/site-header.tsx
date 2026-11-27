"use client";
import { useEffect, useRef, useState } from "react";

export function SiteHeader() {
  const [open, setOpen] = useState(false);
  const [onLight, setOnLight] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const hero = document.getElementById("hero-vista");
    if (!hero) return;
    const updateTheme = () =>
      setOnLight(hero.getBoundingClientRect().bottom <= 90);
    updateTheme();
    window.addEventListener("scroll", updateTheme, { passive: true });
    window.addEventListener("resize", updateTheme);
    return () => {
      window.removeEventListener("scroll", updateTheme);
      window.removeEventListener("resize", updateTheme);
    };
  }, []);
  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape" && open) {
        setOpen(false);
        toggle.current?.focus();
      }
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [open]);
  return (
    <header className={`site-header${onLight ? " is-light" : ""}`}>
      <div className="header-inner section-shell">
        <a
          className="brand"
          href="#main"
          onClick={() => setOpen(false)}
          aria-label="Hyperlooms home"
        >
          Hyperlooms
        </a>
        <button
          ref={toggle}
          className="menu-toggle"
          aria-expanded={open}
          aria-controls="primary-navigation"
          onClick={() => setOpen(!open)}
          aria-label={open ? "Close navigation" : "Open navigation"}
        >
          <span />
          <span />
        </button>
        <nav
          id="primary-navigation"
          className={`primary-navigation${open ? " is-open" : ""}`}
          aria-label="Main navigation"
        >
          <a href="#network" onClick={() => setOpen(false)}>
            The network
          </a>
          <a href="#approach" onClick={() => setOpen(false)}>
            Our approach
          </a>
          <a href="#progress" onClick={() => setOpen(false)}>
            Build progress
          </a>
          <a href="/console" onClick={() => setOpen(false)}>
            Console
          </a>
          <a
            className="header-cta"
            href="https://github.com/appalla-karthik/Hyperlooms"
            target="_blank"
            rel="noopener noreferrer"
            onClick={() => setOpen(false)}
          >
            Explore GitHub <span aria-hidden="true">↗</span>
            <span className="sr-only"> (opens in a new tab)</span>
          </a>
        </nav>
      </div>
    </header>
  );
}
