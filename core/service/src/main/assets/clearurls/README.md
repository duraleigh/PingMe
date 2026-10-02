# ClearURLs rules

`data.minify.json` is the ClearURLs rule set, fetched on 2026-10-02 from
https://rules2.clearurls.xyz/data.minify.json (source: https://github.com/ClearURLs/Rules).
It is licensed under the GNU Lesser General Public License v3.0; the licence text is in
`LICENSE` beside it. PingMe uses it to strip tracking parameters from links
(UI_DESIGN.md 10.11). Refresh it with `./gradlew :core:service:updateClearUrls`.
