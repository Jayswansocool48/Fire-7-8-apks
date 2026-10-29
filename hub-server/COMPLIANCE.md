# Hub compliance

Covers all three parts: the Roku channel (`hub-tv`), the Hub server (`hub-server`) and Hub Remote (`hub-remote`).
Hub has no accounts, no payments, no emails, no ads and no analytics. Several items below don't apply for that reason, and each says so.

| # | Item | Status |
| --- | --- | --- |
| 1 | Privacy policy | Done. `LEGAL["privacy"]` in `hub_server.py`, shown on the control page (Privacy and terms), on the TV (Settings › Privacy and terms) and in Hub Remote (About Hub). |
| 2 | Terms of service | Done. `LEGAL["terms"]`, shown in the same three places. |
| 3 | Refund policy | Not needed: Hub is free with nothing to buy. The terms say so. |
| 4 | Cookie policy | Not needed: the control page sets no cookies. It keeps the last status in `localStorage` only to draw the page before the server answers. |
| 5 | Cookie consent banner | Not needed: no cookies, no tracking. |
| 6 | Checkbox consents on forms | Done where data leaves your network: "Share weather, calendar and shows with the AI" is an unticked checkbox. Remove Hub needs you to type REMOVE. |
| 7 | Collect no unnecessary data | Done. Hub asks for no name, email or age. "Your name" is optional and only feeds the greeting. |
| 8 | Audit third-party SDKs | Done. The server uses the Python standard library plus Playwright (Apache 2.0). Hub Remote uses no libraries. The control page loads no outside scripts. Outside services: Open-Meteo, TVmaze, iTunes Search, AniList, YouTube RSS, Bing, GitHub, and Anthropic or Ollama when you choose them. The privacy policy lists each one. |
| 9 | Remove dark patterns | Done. No nagging, no pre-ticked sharing, Undo instead of hidden confirm traps, and Remove Hub sits in plain view. |
| 10 | Remove hidden fees | Not needed: free. |
| 11 | Remove fake reviews | Done: Hub shows no reviews or testimonials. |
| 12 | Remove unsupported claims | Done. Recommendations say why ("Because you saved The Office"), and the README states limits. |
| 13 | Alt text | Done. Posters and icons on the control page carry `alt` or `aria-label`; Hub Remote sets `contentDescription` on image-only controls. |
| 14 | Color contrast | Done. Foreground `#ECEDF0` on ink `#111419` is about 16:1; dim text `#898C94` on ink is about 5.6:1. |
| 15 | Full keyboard navigation | Done. Control page: Tab through every control, arrow keys drive the TV, 1 to 4 switch tabs, Ctrl+K opens the command menu, Esc closes sheets. The TV runs on the Roku remote's D-pad. |
| 16 | Business details | Done: the privacy policy names the maker (Jayswansocool48 on GitHub) and says there's no company. |
| 17 | Age consent for kids' data | Not needed: no sign-up and no personal data. The privacy policy says so under Children. |
| 18 | Unsubscribe link in emails | Not needed: Hub sends no email. |
| 19 | License every font and image | Done. Geist ships with its SIL Open Font License file (`hub-tv/fonts`, `hub-server/fonts`, `hub-remote/src/main/assets/fonts`). Every image in `hub-tv/images` and the app icons were drawn for Hub. The preset wallpapers render from code. |
| 20 | Data deletion requests | Done. Delete any item in place; Remove Hub deletes everything the server keeps; `install.sh --uninstall` does the same on Debian; Uninstall Hub Remote clears the tablet. |

## Legal holes (standards 4a)

| Hole | Status |
| --- | --- |
| Self-hosted fonts | Done. The control page loads `/fonts/*.woff` from the server. No Google Fonts or CDN requests; a Playwright test fails on any outside request at page load. |
| Session-replay keystroke capture | Not present: no analytics or replay tools. |
| Age check on sign-up | Not needed: no sign-up. |
| CAN-SPAM email | Not needed: no email. |
| Renewal terms at checkout | Not needed: no checkout. |
| Upload disclaimer | Done. Above the photo picker (control page and Hub Remote): send only what you have the right to show, it stays in your Sent to TV folder, and you can delete it there. |

## Security

- No logins or sessions exist; the server listens on your home network only. Run it behind your router, never port-forwarded.
- The AI key lives in `config.json` (mode 600) on the server. `/api/status` reports only `hasKey`; the key never reaches the page, the TV or the tablet.
- Every change runs through the server's own checks (accent colors, row ids and settings get validated there), not the page's.
- POSTs accept an `Idempotency-Key`; the page and Hub Remote send one, so a retried upload or add runs once.
- Removing the channel from the TV needs the TV's developer password, sent only to the TV's own installer page.
