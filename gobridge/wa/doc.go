// SPDX-License-Identifier: AGPL-3.0-or-later

// Package wa is PingMe's gomobile surface over whatsmeow, the WhatsApp Web library
// (BUILD_PLAN.md Phase 6, network 1; DESIGN.md 6.1).
//
// As with package gm, everything that crosses into Kotlin is JSON (the DTO types in
// convert.go) and everything whatsmeow reports comes back through EventSink.OnEvent as
// one JSON object per event. The Kotlin side (connectors/whatsapp) turns those into
// ConnectorEvents. The device keys live in a SQLite file Kotlin names, through the pure
// Go driver, so no native SQLite is needed.
package wa
