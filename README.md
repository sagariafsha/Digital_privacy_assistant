# Digital Privacy Assistant Website

This folder is the website deployment copy. It contains the existing browser interface and Java API; the Android and Flutter projects are not part of this deployment.

## Run locally on Windows

1. Install Java 17 and Maven.
2. Double-click `Run-Privacy-Assistant.bat`. The launcher starts the web server using the configured Google Web Client ID and opens `http://127.0.0.1:5500`.
3. Keep the backend window open while using the website. Connect Google and approve Gmail read-only access when prompted.

The local OAuth client is configured for the origin `http://127.0.0.1:5500`; use that exact address. Do not use the client secret in the website. The short-lived Google access token is held in page memory and sent as a bearer token for Gmail-backed requests. Refreshing the page or token expiry requires the user to connect Google again.

MySQL persistence is optional for local viewing. Set `DB_URL`, `DB_USER`, and `DB_PASSWORD` to enable saved account results and deletion-request tracking.

## Google OAuth for the classroom demo

The app requests only `https://www.googleapis.com/auth/gmail.readonly`. For a class demonstration, leave the OAuth audience in Testing and add only the Gmail accounts that should use the demo as test users. Google limits a Testing app to 100 test users, and authorizations for Gmail scopes expire after seven days. Public use may require Google verification; Gmail read-only is a restricted scope, and server-side handling of restricted data can require an independent security assessment. Keep the app in testing until those requirements have been addressed.

The server identifies each request from the connected Gmail profile and uses that email to scope database reads and writes. It does not keep Gmail access tokens between requests.

## Free demonstration hosting

`render.yaml` describes a free Render web service. Free web services can sleep after 15 minutes without traffic and may take about a minute to wake up. Their local filesystem is temporary, so the database must be external.

For MySQL, create a small Aiven MySQL service and set these environment variables in Render:

- `GOOGLE_CLIENT_ID` — the OAuth Web client ID
- `DB_URL` — `jdbc:mysql://HOST:PORT/DATABASE?sslmode=require&serverTimezone=UTC`
- `DB_USER` — database user
- `DB_PASSWORD` — database password

Use the host, port, database, user and password from Aiven's connection details. Keep credentials in the hosting provider's environment settings; do not put them in this folder or commit them to GitHub. Aiven's free MySQL service has limited resources and may be stopped if left unused for an extended period.

After the first deployment, add the exact `https://<your-render-host>` origin to the OAuth Web client and add the required demo accounts under OAuth test users. Then test a fresh Google connection, a Gmail scan, account isolation between two test accounts, and deletion-request tracking.
