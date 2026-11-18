# Research notes: tracking, copyright and India's DPDP law

Engineering notes, not legal advice. Have counsel review before a public launch.
Items marked (verify) come from general knowledge and were not re-checked against a primary source.

## 1. How Brave and Google approach browser telemetry

| | Brave | Google Chrome |
|---|---|---|
| Browsing history | Not collected. Brave states P3A does not gather browsing history or search queries. | Stays local unless Chrome Sync is on; sync is tied to a Google Account (verify). |
| Product analytics | P3A: answers to fixed multiple-choice questions (for example, number of open tabs), each sent as an independent report so Brave cannot link one user's answers together. Switch in `brave://settings/privacy`. | Usage statistics and crash reports are an opt-in prompt at setup (verify). |
| Identity | No account needed. | Optional Google sign-in, which ties data to a person. |
| Critics' point | A third-party write-up argues P3A is on by default, which may not meet strict consent rules in the UK/EU. | Large first-party data holder; the ad business depends on user data. |

Sources: [Brave P3A](https://brave.com/privacy-preserving-product-analytics-p3a/), [Brave support: What is P3A](https://support.brave.app/hc/articles/9140465918093), [critique of the default setting](https://news.lavx.hu/article/brave-s-default-analytics-and-opaque-settings-challenge-its-privacy-first-narrative).

**What WBrowser does differently.** Brave and Chrome deliberately avoid per-user browsing history. WBrowser's admin feature is the opposite: it records identified, per-user browsing. That is lawful only as an explicit, informed, withdrawable, opt-in service, so the design is built around that:

- Nothing is collected before the first-launch screen and an explicit tick-box. Declining still gives a fully working browser.
- Search queries need a second, separate opt-in that is off by default.
- Private tabs are never recorded.
- Sensitive URL parts (token, code, key, session and similar parameters, fragments, credentials in the URL) are removed on the device, and again on the server.
- Passwords, cookies, auth tokens and page content are never collected.
- Google sign-in uses the official Credential Manager / OpenID Connect flow. The server verifies the ID token with Google and keeps only the subject, name and email. The token is not stored.
- The admin side has role-based access, an audit log of every read of a person's data, and automatic retention deletion.

## 2. Copying features: copyright and licences

General principles (Indian Copyright Act 1957 and the international norm): copyright protects expression (source code, art, text, UI artwork), not ideas, functions or methods of operation (verify the exact statute wording). Implementing the same feature idea, such as ad blocking or a shield toggle, with your own code is generally permitted.

Practical rules followed in this project:

1. **Do not copy code, icons, text or branding** from Brave, Chrome or Firefox. The shield and history features here are original code.
2. **Trademarks.** Do not use "Brave", "Chrome" or "Firefox" names or logos. The Gecko engine is MPL-2.0 and Mozilla's marks are not licensed by it. The app is branded "WBrowser".
3. **Gecko (GeckoView)** is MPL-2.0. You may ship it in a closed app. Modified MPL files must be published, and a licence notice is required. Add an open-source licences screen before release.
4. **uBlock Origin** is GPLv3. It is downloaded from addons.mozilla.org at runtime and is not bundled in the APK. Keep it that way, or distribute GPL source if it is ever bundled.
5. **Filter lists** (EasyList etc.) have their own licences (mostly CC BY-SA or GPL). Keep attribution if they are redistributed.
6. **Google Sign-In** is governed by the Google API Services User Data Policy. Request only name and email, and disclose the use in the privacy policy and OAuth consent screen.

## 3. India's Digital Personal Data Protection (DPDP) regime

Status as of this writing: the DPDP Act, 2023 is in force in part. The DPDP Rules, 2025 were notified on 13 Nov 2025. The Data Protection Board provisions applied immediately, consent-manager rules apply after about 12 months, and the main obligations (notice, security safeguards, breach reporting, retention, children's data) apply after 18 months, around mid-May 2027. Until then the older SPDI Rules 2011 continue to apply (see [Deccan Herald](https://www.deccanherald.com/amp/story/india%2Fcentre-notifies-dpdp-rules-implementation-planned-in-phases-spread-over-12-18-months-3798465), [Storyboard18](https://www.storyboard18.com/amp/digital/breaking-govt-notifies-final-dpdp-rules-sets-staggered-rollout-with-key-obligations-for-data-fiduciaries-84201.htm), [Legal500](https://www.legal500.com/firms/237811-saga-legal/c-india/news-and-developments/the-privacy-timer-starts-now-analysing-the-dpdp-rules-and-its-18-month-countdown)). Build for the DPDP standard now.

Browsing history, URLs, search queries and identity together are personal data, and whoever runs this service is the **Data Fiduciary**.

| DPDP requirement | How this implementation addresses it |
|---|---|
| Consent must be free, specific, informed, unconditional, unambiguous, with a clear affirmative action | First-launch screen: plain-language notice, un-ticked checkbox, separate un-ticked checkbox for searches, a "use without sharing" path. |
| Notice of what data, why, how to withdraw and complain | `PrivacyNotice` text on first launch and in Account and privacy. **You must still publish a full privacy policy with the fiduciary's contact details and the Data Protection Board complaint route.** |
| Withdrawal as easy as giving consent | Two switches in Settings > Account and privacy. Withdrawing search consent also deletes stored queries. |
| Purpose limitation and data minimisation | Only URLs (sanitised), titles, timings, name, device model. No content, cookies, tokens, passwords. |
| Right to access and to erasure | `DELETE /api/v1/me` ("Delete my data and leave") erases the server copy. `GET /api/v1/me/export` returns all data held. There is no in-app export button yet, so serve access requests through the admin team. |
| Storage limitation | `RETENTION_DAYS` (default 180) purge job. |
| Reasonable security safeguards | TLS enforced in release builds, hashed device secrets, Keystore-wrapped secret on device, bcrypt admin passwords, lockout, rate limiting, CSP, RBAC, audit log. |
| Consent record | `consent_log` table (purpose, granted, policy version, time). |
| Breach notification to the Board and affected people | Process item, not code. Write an incident runbook before launch. |
| Children (under 18) need verifiable parental consent | Not implemented. Do not offer this to minors without adding an age gate. |
| Grievance officer, and Significant Data Fiduciary duties if designated | Organisational. |

**Employer or monitoring use.** If this tracks employees or managed-device users, "consent" can be doubtful because of the power imbalance. Rely on a documented lawful basis, tell people clearly, and keep it proportionate. Never use it on people who have not been told.

## 4. Known limits

- The Android app was compiled and packaged here but not exercised on a physical device or emulator. Test onboarding, tracking, offline queueing and Google sign-in on a device.
- Google sign-in needs a Google Cloud OAuth *Web* client ID (`GOOGLE_WEB_CLIENT_ID`) and an Android OAuth client for the app's package and signing SHA-1. It is hidden until the ID is supplied.
- The server stores data in SQLite (WAL). That is fine for thousands of users; move to PostgreSQL for large fleets.
- A hard process kill during a page view can lose up to 60 seconds of that view's time, because checkpoints are written every 60 seconds.
