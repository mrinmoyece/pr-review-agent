## Summary

<!-- Explain the user-visible behavior and why this change is needed. -->

## Risk and security impact

<!-- Describe trust-boundary, permissions, data-handling, prompt, or deployment changes. Use N/A if none. -->

## Validation

<!-- List automated and manual validation performed. -->

## Checklist

- [ ] Tests cover changed behavior and failure paths.
- [ ] Untrusted input and LLM output are treated as data, not control flow.
- [ ] No credential, permission, or write scope was broadened unnecessarily.
- [ ] Documentation and deployment configuration are updated.
- [ ] Backward compatibility and rollback were considered.
