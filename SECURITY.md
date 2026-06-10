# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately to **security@firemoot.com**
or via GitHub's private vulnerability reporting on this repository.

Please do not open public issues for security reports.

We aim to acknowledge reports within 48 hours and to provide a remediation
timeline within 7 days.

## Supported versions

Pre-1.0: only the latest release receives security fixes.

## Scope notes

Firemoot is object-storage-adjacent (S3-compatible media store) and exposes a
WebSocket gateway; reports in the following areas are particularly welcome:

- Authentication/authorisation bypass (JWT verification, channel membership checks)
- Presigned upload policy bypass (MIME/size limits)
- Webhook signature forgery
- Admin UI session handling and CSRF
