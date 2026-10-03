// SPDX-License-Identifier: AGPL-3.0-or-later

// Package gv is the Google Voice bridge (BUILD_PLAN.md Phase 6, network 4): libgv from
// mautrix-gvoice, bound to Kotlin by gomobile. The sign-in is the Google account's
// cookies from voice.google.com; threads and their messages come from Google Voice's own
// web API, and a realtime channel says when something new is there.
//
// Ids: a chat is Google Voice's thread id ("t.+15555550123" for one person, "g.…" for a
// group); a message is "<thread id>/<item id>"; a person is their phone number.
package gv
