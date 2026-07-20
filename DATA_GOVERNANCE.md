# Data Governance

## Collection and retention

Field source folders are read-only inputs. Ingestion copies supported images into
a SHA-256-addressed batch and records relative source provenance. Never commit
real resident images, credentials, device secrets, or absolute workstation paths.
Access to raw batches should be limited to authorized collection and privacy
review roles, with OS permissions denying general read access.

## Privacy

Potential faces, license plates, documents, and name tags require reviewed mask
annotations. Masked derivatives are separate files; originals are never
overwritten. A `masked` dataset version fails closed when an eligible image lacks
its derivative. Automated detectors may propose masks but cannot approve them.

## Dataset approval

A version includes only reviewer-approved annotations, excludes non-canonical
duplicates, applies the selected privacy policy, and assigns whole source-batch
groups to one split. The manifest records source batches, exclusions, label and
quality distributions, seed, and relative lineage. Reviewers approve the
manifest and QA report before training.

## Audit and incident response

Retain ingestion manifests, error ledgers, task files, annotation revisions, QA
reports, duplicate decisions, privacy provenance, and dataset manifests according
to the organization retention schedule. On accidental disclosure, revoke access,
quarantine affected raw and derivative batches, identify downstream versions
through lineage, and rebuild them after privacy review.
