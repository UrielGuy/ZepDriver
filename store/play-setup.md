# Google Play setup, step by step

About 30–45 minutes the first time. Do the parts in order.

## Part 1: Make the upload key (5 min)

1. **Check for Java's `keytool`.** Open a terminal (Mac: Terminal; Windows: PowerShell):
   ```
   keytool -help
   ```
   If it says "command not found", install the free Temurin JDK from
   **adoptium.net** (latest LTS, default options), then open a new terminal.
2. **Pick a strong password** (letters and digits only, 20+ characters) and save
   it in your password manager now.
3. **Create the key** in a folder you'll remember (e.g. Documents). It asks for
   the password twice:
   ```
   keytool -genkeypair -v -storetype PKCS12 -keystore zepdriver-upload.p12 -alias upload -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=ZepDriver upload key"
   ```
   The alias must stay `upload`.
4. **Back up `zepdriver-upload.p12`** (e.g. as an attachment in your password
   manager). Every Play upload needs this file plus the password. If they're
   lost, Play can reset the key, but only through a support request.
5. **Copy the key as text** to the clipboard:
   - Mac: `base64 -i zepdriver-upload.p12 | pbcopy`
   - Windows (PowerShell):
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\zepdriver-upload.p12")) | Set-Clipboard`

## Part 2: Put the key in GitHub (3 min)

1. Open **github.com/UrielGuy/ZepDriver/settings/secrets/actions** and add two
   secrets with **New repository secret**:

   | Name | Value |
   |---|---|
   | `ZEP_UPLOAD_KEYSTORE_B64` | paste (the clipboard from Part 1, step 5) |
   | `ZEP_UPLOAD_KEY_PASSWORD` | your password |

2. Go to **Actions → Android CI → Run workflow**. Leave "Also upload to Google
   Play" unticked.
3. About 6 minutes later, open
   **github.com/UrielGuy/ZepDriver/releases/tag/latest-debug**. The notes should
   say the bundle is **"signed with the upload key"**.
4. Download **zepdriver-release.aab**.

## Part 3: First upload to Play (10 min)

1. **Uninstall the old test app called "ZepDriver"** from your phone. It would
   block the Play version. New test builds are called "ZepDriver Test" and sit
   next to it.
2. In **play.google.com/console**, choose **Create app**:
   - Name `ZepDriver`, English (United States), **App**, **Free**.
   - Tick the two declarations, then **Create app**.
3. Go to **Test and release → Testing → Internal testing → Create new release**.
   - If asked about app signing, keep **"Use Google-generated key"**.
   - Upload `zepdriver-release.aab`, then **Next → Save and publish**.
   - If Play asks for app content first, use
     [play-console-answers.md](play-console-answers.md).
4. On the **Testers** tab, create an email list with your Google account. Open
   the **join link** on your phone and install ZepDriver from Play.

## Part 4: Let Play installs open the sealed data (2 min)

1. In Play Console, go to **Test and release → App integrity → App signing**.
   Copy the **SHA-256** under **"App signing key certificate"** (not the
   upload key one).
2. In GitHub, go to **Settings → Secrets and variables → Actions → Variables tab
   → New repository variable**. Name it `ZEP_EXTRA_CERTS` and paste the
   SHA-256.

Without this, Play installs would show "Naughty!" once 2027 data arrives.

## Part 5: Automatic Play uploads (15 min)

1. At **console.cloud.google.com**, create a project, e.g. `zepdriver-play`.
2. Search **"Google Play Android Developer API"** and click **Enable**.
3. Go to **IAM & Admin → Service accounts → Create service account**. Name it
   `github-play-upload`, skip the roles, then **Done**.
4. Click the account, then **Keys → Add key → Create new key → JSON**. A file
   downloads.
5. In **Play Console → Users and permissions → Invite new users**:
   - Paste the service account's email (it ends in `@…iam.gserviceaccount.com`).
   - Under **App permissions**, add ZepDriver with **"Release apps to testing
     tracks"**, then **Invite user**.
6. In GitHub, add a secret named `PLAY_SERVICE_ACCOUNT_JSON`. Paste the
   **entire JSON file** as its value.
7. **Test it:** go to **Actions → Android CI → Run workflow** and tick **"Also
   upload to Google Play"**. A new version should appear in Internal testing
   within about 10 minutes.
8. Delete the downloaded JSON file from your computer. GitHub has it now.

## After that

1. **Store listing:** everything is in [assets/](assets/): texts to paste,
   the 512 px icon, the 1024×500 feature graphic and the phone screenshots.
2. **App content:** fill in every section under **Policy → App content** from
   [play-console-answers.md](play-console-answers.md).
3. **Closed testing:** run a closed track with **12+ testers for 14 days**
   (personal-account rule), then **Apply for production**.
