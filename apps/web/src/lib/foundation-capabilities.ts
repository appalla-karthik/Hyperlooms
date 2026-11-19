export const foundationCapabilities = [
  "Product and architecture record",
  "Spring Boot control-plane baseline",
  "Go provider-agent process baseline",
  "PostgreSQL migration foundation",
  "S3-compatible local artifact storage",
  "Automated quality gates",
] as const;

export function capabilityCount(): number {
  return foundationCapabilities.length;
}
