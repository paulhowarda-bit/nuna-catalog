# AWS sign-in for the tests by certificate (IAM Roles Anywhere)

With this set up, the tests that run against AWS (`-Ps3=aws`) get their credentials from a certificate on this
machine: no password, no browser and no long-lived access keys. Each credential lasts an hour and renews itself.
The role it signs into can touch only buckets and roles named `nuna-test-*`, which is how the tests name everything
they make.

## Already done on this machine

`C:\Users\paulh\.nuna-aws` holds a certificate authority of its own and one certificate it signed:

| File | What it is | Leaves the machine? |
|---|---|---|
| `ca.pem` | the CA's public certificate (`CN=nuna home CA`, ten years) | yes: you paste it into AWS in step 1 |
| `ca.key` | the CA's private key | never |
| `nuna-tests.pem` | the tests' certificate (`CN=nuna-tests`, one year, to 2027-11-27) | no |
| `nuna-tests.key` | the tests' private key | never |

## What you do in AWS (about 10 minutes)

Sign in to the AWS console as `paul-admin`. In the top right corner, check the region says **N. Virginia**
(us-east-1); if it does not, click it and pick **US East (N. Virginia)**. Roles Anywhere works per region, and the
tests run in us-east-1. Do the three parts in this order: each one needs the previous one's ARN.

Keep a scratch note open for three ARNs. An ARN is a long name that starts `arn:aws:`. Each page shows its ARN with a
small copy icon beside it. None of the three is a secret.

### Part 1: the trust anchor (tells AWS to trust certificates from your CA)

1. In the search box at the top of the console, type `Roles Anywhere` and click **IAM Roles Anywhere**.
   (It can also be reached from the IAM page: left menu, **Roles**, then scroll to the bottom of the page, where a
   **Roles Anywhere** panel has a **Manage** button.)
2. Click **Create a trust anchor**. (On a first visit the page shows a getting-started panel; the button is in it.)
3. **Trust anchor name**: `nuna-home-ca`.
4. **Certificate authority (CA) source**: choose **External certificate bundle**.
5. A box labelled **External certificate bundle** appears. Open `C:\Users\paulh\.nuna-aws\ca.pem` in Notepad, select
   all of it (Ctrl+A), copy it, and paste it into the box. It starts `-----BEGIN CERTIFICATE-----` and ends
   `-----END CERTIFICATE-----`; paste both lines and everything between them.
6. Leave **Notification settings** and **Tags** as they are.
7. Click **Create a trust anchor** at the bottom.
8. Back on the Roles Anywhere page, under **Trust anchors**, click `nuna-home-ca`. Copy its **ARN**
   (`arn:aws:rolesanywhere:us-east-1:...:trust-anchor/...`) into your note as **trust anchor ARN**.

### Part 2: the role the tests sign into

1. In the search box, type `IAM` and click **IAM**. In the left menu click **Roles**, then **Create role** (top right).
2. **Trusted entity type**: choose **Custom trust policy**. A text box appears holding a short policy.
3. Click in that box, select everything (Ctrl+A), delete it, and paste the policy below. Then replace
   `PASTE-TRUST-ANCHOR-ARN-HERE` with the trust anchor ARN from part 1, keeping the quotation marks around it.

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Principal": { "Service": "rolesanywhere.amazonaws.com" },
         "Action": ["sts:AssumeRole", "sts:TagSession", "sts:SetSourceIdentity"],
         "Condition": {
           "ArnEquals": { "aws:SourceArn": "PASTE-TRUST-ANCHOR-ARN-HERE" },
           "StringEquals": { "aws:PrincipalTag/x509Subject/CN": "nuna-tests" }
         }
       }
     ]
   }
   ```

   This lets in only a certificate signed by your CA whose name is `nuna-tests`.
   If the box shows a red error, the usual cause is a missing quotation mark around the ARN.
4. Click **Next**. The **Add permissions** page lists AWS's own policies: tick none of them, and click **Next**.
5. **Role name**: `nuna-test-runner`. **Description**: `Tests that run against AWS, signed in by certificate`.
6. Scroll down and click **Create role**.
7. A green banner says the role was made. Click **View role** in the banner (or find `nuna-test-runner` in the
   list and click it).
8. On the role's page, copy its **ARN** (`arn:aws:iam::...:role/nuna-test-runner`) into your note as **role ARN**.
9. Still on the role's page, open the **Permissions** tab. Click **Add permissions** (on the right), then
   **Create inline policy**.
10. At the top of the policy editor, click **JSON** (beside **Visual**). Select everything in the box, delete it, and
    paste this:

    ```json
    {
      "Version": "2012-10-17",
      "Statement": [
        {
          "Sid": "TestBuckets",
          "Effect": "Allow",
          "Action": "s3:*",
          "Resource": ["arn:aws:s3:::nuna-test-*", "arn:aws:s3:::nuna-test-*/*"]
        },
        {
          "Sid": "TestRoles",
          "Effect": "Allow",
          "Action": [
            "iam:CreateRole", "iam:DeleteRole", "iam:GetRole", "iam:TagRole",
            "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:ListRolePolicies"
          ],
          "Resource": "arn:aws:iam::*:role/nuna-test-*"
        },
        {
          "Sid": "SignIntoTestRoles",
          "Effect": "Allow",
          "Action": "sts:AssumeRole",
          "Resource": "arn:aws:iam::*:role/nuna-test-*"
        },
        {
          "Sid": "WhoAmIAndListing",
          "Effect": "Allow",
          "Action": ["sts:GetCallerIdentity", "s3:ListAllMyBuckets"],
          "Resource": "*"
        }
      ]
    }
    ```

11. Click **Next**. **Policy name**: `nuna-test-runner-permissions`. Click **Create policy**.

### Part 3: the Roles Anywhere profile (joins the certificate to the role)

1. Go back to **IAM Roles Anywhere** (search box, as in part 1).
2. Scroll to **Profiles** and click **Create a profile**.
3. **Profile name**: `nuna-test`.
4. **Roles**: open the list and pick `nuna-test-runner`.
5. Leave **Session policies** empty. If the page shows **Duration** (it may be under **Advanced** or
   **Session settings**), leave it at 3600 seconds, one hour.
6. Click **Create a profile**.
7. Click `nuna-test` in the **Profiles** list and copy its **ARN**
   (`arn:aws:rolesanywhere:us-east-1:...:profile/...`) into your note as **profile ARN**.

### Part 4: hand over

Send the three ARNs from your note. They are names, not secrets.

## What the session does with them

1. Asks to download AWS's credential helper, `aws_signing_helper.exe`, from the IAM Roles Anywhere documentation
   ("Get temporary security credentials from IAM Roles Anywhere"), into `C:\Users\paulh\.nuna-aws`.
2. Adds a profile to `C:\Users\paulh\.aws\config`:

   ```ini
   [profile nuna-test]
   region = us-east-1
   credential_process = C:\Users\paulh\.nuna-aws\aws_signing_helper.exe credential-process --certificate C:\Users\paulh\.nuna-aws\nuna-tests.pem --private-key C:\Users\paulh\.nuna-aws\nuna-tests.key --trust-anchor-arn <trust anchor ARN> --profile-arn <profile ARN> --role-arn <role ARN>
   ```

3. Checks it: `aws sts get-caller-identity --profile nuna-test` should name `assumed-role/nuna-test-runner/...`.
4. From then on the AWS tests run with `-Ps3=aws -Pnuna.aws.profile=nuna-test`, with no sign-in.

The inline policy was written from the calls the tests make and has not yet been tried against AWS. If a test is
refused, its log names the action (`... is not authorized to perform: <action>`), and that action is added to the
policy.

## Later

- The tests' certificate runs out on 2027-11-27. A new one is signed with `ca.key` the same way; nothing changes in
  AWS.
- To stop it at once: in IAM Roles Anywhere, open `nuna-home-ca` and click **Disable**. Deleting the role
  `nuna-test-runner` also stops it.
