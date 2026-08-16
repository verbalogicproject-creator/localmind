package com.verbalogix.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.verbalogix.assistant.data.capability.CapabilityState
import com.verbalogix.assistant.ui.theme.AmberTokens

/**
 * The one panel shape this design has.
 *
 * A surface step plus a hairline outline, and nothing else -- no shadow, no elevation
 * overlay, no gradient. `visual_direction` in the contract reads
 * `dense-flat-amber-technical-no-decorative-gradients`, and the flat part is enforced
 * here rather than remembered at each call site.
 */
@Composable
fun AmberPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(HAIRLINE, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(AmberTokens.radiusLarge),
        content = content,
    )
}

/**
 * The panel border. One device pixel at mdpi, and the only stroke width in the design:
 * a flat surface that is separated by a step and a hairline has no second weight to
 * choose from, which is what keeps "dense flat" from drifting into "boxed".
 */
private val HAIRLINE = 1.dp

/**
 * A status, stated in words AND marked with a glyph.
 *
 * NEVER COLOUR ALONE. `design-tokens.json` says
 * `status: text-and-icon-never-color-only`, and this is where that is kept honest --
 * roughly one in twelve men cannot separate the amber from the red, and a screen
 * reader conveys no colour at all. The [mark] is a text glyph rather than a vector
 * icon so it inherits font scaling and needs no icon dependency.
 *
 * The mark is hidden from accessibility and the label carries the whole meaning,
 * because "check mark, grounded" read aloud is worse than "grounded".
 */
// `modifier` comes first among the optional parameters, which is the Compose API
// guideline and what lint's ModifierParameter rule checks. Not style for its own sake:
// callers that pass a modifier positionally would otherwise land on `tint`, and a
// Color and a Modifier are different enough types that the mistake is a compile error
// here and a silent reorder in a future signature change.
@Composable
fun StatusLine(
    mark: String,
    label: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mark,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/**
 * Why an action cannot be taken, and what would enable it.
 *
 * Both halves are required by `unavailable-actions-render-reason-and-required-capability`.
 * The capability id is shown in monospace because it is an identifier the user may need
 * to quote back to whoever operates their Foundry, not prose.
 *
 * This renders an ABSENCE, deliberately and visibly. The alternative -- hiding the
 * surface entirely -- makes a missing capability indistinguishable from a feature that
 * was never built, and leaves someone with a paired Foundry no way to tell that the
 * pairing has silently lapsed.
 */
@Composable
fun UnavailableNotice(
    state: CapabilityState.Unavailable,
    modifier: Modifier = Modifier,
) {
    AmberPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AmberTokens.panelPadding),
            verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        ) {
            StatusLine(
                mark = MARK_UNAVAILABLE,
                label = "Unavailable",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Requires: ${state.requiredCapability}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * An empty state that says the specific thing rather than a generic one.
 */
@Composable
fun EmptyNotice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AmberTokens.panelPadding),
        verticalArrangement = Arrangement.spacedBy(AmberTokens.baseUnit),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The 48dp floor from `android_minimum_target_dp`, applied as a MINIMUM SIZE.
 *
 * `defaultMinSize` and not `size`: a target must be at least 48dp and is free to be
 * larger, and forcing an exact size would clip a row whose text has grown at font
 * scale 2.0 -- trading an accessibility rule for an accessibility bug.
 */
fun Modifier.minimumTouchTarget(): Modifier =
    this.defaultMinSize(minWidth = AmberTokens.minTouchTarget, minHeight = AmberTokens.minTouchTarget)

// Text marks, not vector icons: they scale with the font setting, need no icon
// artifact, and cannot go missing when an icon library stops being transitive -- which
// has already happened once in this repo with material-icons-core.
const val MARK_OK = "●"
const val MARK_UNAVAILABLE = "○"
const val MARK_ERROR = "▲"
const val MARK_INFO = "■"
