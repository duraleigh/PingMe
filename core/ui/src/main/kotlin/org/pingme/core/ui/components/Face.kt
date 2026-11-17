// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.ui.components

/** One member of a group as its avatar shows them: a name for initials, and a photo when there is one. */
data class Face(
    val name: String,
    val photo: String? = null,
)
