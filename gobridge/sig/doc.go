// SPDX-License-Identifier: AGPL-3.0-or-later

// Package sig is the Signal bridge (BUILD_PLAN.md Phase 6, network 3): signalmeow from
// mautrix-signal over Signal's own libsignal, bound to Kotlin by gomobile.
//
// PingMe links as a new Signal device: the Signal app on the phone scans a QR code shown
// by PingMe (there is no code to type), and offers to transfer the message history,
// which arrives as an archive that becomes the chat list and the first pages of every
// chat. After that everything is live: messages, receipts, typing, reactions, edits,
// deletes, and group changes come over Signal's websocket.
//
// Ids: a one-to-one chat is the other person's account id (a UUID); a group is Signal's
// group identifier (44 characters). A message is "<sender id>:<timestamp in ms>", which
// is how Signal itself refers to a message. Every event reaches Kotlin as one JSON object
// through EventSink, told apart by its "type".
package sig
