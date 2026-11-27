# Contributing to Hyperlooms

The Phase 0 foundation is complete; product execution work is still ahead. Changes should preserve explicit module boundaries and update the relevant architecture decision when they alter an agreed system property.

## Development workflow

1. Create a focused branch from `main`.
2. Keep commits small and use an imperative subject.
3. Add or update tests with behavior changes.
4. Run the affected formatter, linter, build, and tests locally.
5. Update documentation when an API, invariant, security assumption, or operational procedure changes.

Recommended commit prefixes are `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, and `chore`.

## Definition of done

A change is done only when:

- acceptance behavior is covered by automated tests where practical;
- logs do not expose secrets or user artifacts;
- API changes are reflected in OpenAPI or Protobuf contracts;
- database changes use a new forward-only Flyway migration;
- errors are observable and actionable;
- documentation distinguishes current behavior from future work;
- CI quality gates pass.

## Module boundaries

- `apps/web` owns browser presentation, not scheduling rules.
- `services/control-plane` owns business state, scheduling decisions, and accounting.
- `agents/provider` owns host discovery and policy-constrained execution, not business accounting.
- `workloads` owns workload-specific Python code, not control-plane state.
- `proto` owns wire compatibility between the control plane and agents.

## Secrets

Never commit credentials, private keys, access tokens, production endpoints, datasets, model weights, or generated local certificates. Use environment variables or an approved secret manager.
