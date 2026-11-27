# 2026-11-26, the AWS tests sign in by certificate (IAM Roles Anywhere)

- `docs/aws-roles-anywhere.md`: a CA of this machine's own, a trust anchor, the role `nuna-test-runner` and the
  profile `nuna-test` in Paul's account, and the CLI profile `nuna-test`, whose credentials come from
  `aws_signing_helper` 1.8.6 (hash and AWS's signature checked). No password, browser or access key.
- A Roles Anywhere session passes its source identity and session tags to every role chained from it, so the roles the
  tests make (`TestS3`'s run and maintenance roles, `DeletePolicyAwsTest`'s bare worker) now allow
  `sts:SetSourceIdentity` and `sts:TagSession` in their trust policies; without them both AWS tests were refused.
- The runner's policy denies it IAM on itself, since its name matches the `nuna-test-*` it may change; checked refused.
- Ran with `-Pheavy -Ps3=aws -Pnuna.aws.profile=nuna-test`: `DeletePolicyAwsTest` 2 and `KmsKeyAwsTest` 1, all
  passing, and no `nuna-test-*` role or bucket left but the runner.
