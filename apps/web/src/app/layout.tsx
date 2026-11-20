import type { Metadata } from "next";
import type { ReactNode } from "react";

import "./globals.css";

export const metadata: Metadata = {
  title: "Hyperlooms - Big ideas. Shared power.",
  description:
    "Building a community-powered GPU network. Connecting people with spare compute to people ready to create. Explore the vision and follow the build.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
