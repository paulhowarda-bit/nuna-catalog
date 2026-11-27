# 2026-11-26, DeletePolicyAwsTest run on AWS (backlog 259)

- `DeletePolicyAwsTest` ran against AWS S3 and IAM in us-east-1 with Paul's CLI profile (`-Pheavy -Ps3=aws`), its
  first run anywhere: 2 tests, both passed, in 49 s.
- What it shows: the bucket policy alone lets a worker role with no S3 policy of its own list and delete beneath the
  catalog's prefix, and nowhere else; `deploy/README.md`'s "Delete rights" rule for AWS stands unchanged.
- No `nuna-test-*` role or bucket was left in the account.
