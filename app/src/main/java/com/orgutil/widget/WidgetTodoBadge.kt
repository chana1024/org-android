package com.orgutil.widget

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.orgutil.R
import com.orgutil.data.datasource.ThemeChoice
import com.orgutil.data.datasource.ThemeChoiceStore

/**
 * Palette resolution for the home-screen widgets. The persisted choice is
 * re-read from [ThemeChoiceStore] on EVERY full update / data change — the
 * same store the Compose app writes — so a widget refresh triggered from a
 * broadcast (app process alive or cold-started by the launcher) always
 * renders the current palette, never a stale one. Layouts are baked per
 * theme: identical view IDs and structure, only colors/drawables differ
 * (launcher-safe: no per-view color reflection actions).
 */
internal object WidgetTheme {

    fun choice(context: Context): ThemeChoice =
        ThemeChoiceStore(context.applicationContext).readChoice()

    @LayoutRes
    fun headerLayout(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.KRAFT_LEDGER -> R.layout.widget_agenda_kraft
        ThemeChoice.CLASSIC -> R.layout.widget_agenda
    }

    @LayoutRes
    fun entryLayout(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.KRAFT_LEDGER -> R.layout.widget_agenda_entry_kraft
        ThemeChoice.CLASSIC -> R.layout.widget_agenda_entry
    }

    @LayoutRes
    fun sectionLayout(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.KRAFT_LEDGER -> R.layout.widget_agenda_section_kraft
        ThemeChoice.CLASSIC -> R.layout.widget_agenda_section
    }

    @LayoutRes
    fun emptyLayout(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.KRAFT_LEDGER -> R.layout.widget_agenda_empty_kraft
        ThemeChoice.CLASSIC -> R.layout.widget_agenda_empty
    }

    @LayoutRes
    fun quickCaptureLayout(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.KRAFT_LEDGER -> R.layout.widget_quick_capture_kraft
        ThemeChoice.CLASSIC -> R.layout.widget_quick_capture
    }
}

/**
 * TODO-keyword badge colors for the Agenda widget, mirroring the in-app
 * [com.orgutil.ui.components.OrgStateChip] palette one-to-one.
 *
 * CLASSIC: the background is a baked rounded-rect drawable per keyword (no
 * bitmap is generated per badge); the text color is applied as an explicit
 * int.
 *
 * KRAFT LEDGER: every keyword badge sits on the ONE shared light #EEEAE0
 * base ([R.drawable.widget_todo_bg_kraft]) with a saturated ink foreground
 * per keyword — the same light-base + colored-ink grammar as the in-app
 * kraft chips, so labels stay legible on selected/running rows.
 *
 * The factory sets BOTH for every row - including unknown and null keywords
 * - so RemoteViews recycling can never leak a previous row's state colors.
 */
internal data class WidgetTodoBadge(
    @DrawableRes val backgroundRes: Int,
    @ColorInt val textColor: Int
) {

    companion object {

        /** Same keyword grammar as OrgStateChip; unknown/null falls back to the neutral pair. */
        fun forKeyword(todo: String?, choice: ThemeChoice): WidgetTodoBadge = when (choice) {
            ThemeChoice.KRAFT_LEDGER -> WidgetTodoBadge(
                backgroundRes = R.drawable.widget_todo_bg_kraft,
                textColor = kraftTextColorFor(todo)
            )
            ThemeChoice.CLASSIC -> WidgetTodoBadge(
                backgroundRes = classicBackgroundFor(todo),
                textColor = classicTextColorFor(todo)
            )
        }

        @DrawableRes
        private fun classicBackgroundFor(todo: String?): Int = when (todo?.uppercase()) {
            "DONE" -> R.drawable.widget_todo_bg_done
            "DOING", "IN-PROGRESS", "STARTED" -> R.drawable.widget_todo_bg_doing
            "VIBING" -> R.drawable.widget_todo_bg_vibing
            "NEXT" -> R.drawable.widget_todo_bg_next
            "TODO" -> R.drawable.widget_todo_bg_todo
            "WAIT", "WAITING" -> R.drawable.widget_todo_bg_wait
            "HOLD" -> R.drawable.widget_todo_bg_hold
            "SANDBAGGING" -> R.drawable.widget_todo_bg_sandbagging
            "PROJ" -> R.drawable.widget_todo_bg_proj
            "AREA" -> R.drawable.widget_todo_bg_area
            "MAYBE" -> R.drawable.widget_todo_bg_maybe
            "DROPPED" -> R.drawable.widget_todo_bg_dropped
            "CANCELLED", "CANCELED" -> R.drawable.widget_todo_bg_cancelled
            else -> R.drawable.widget_todo_bg_unknown
        }

        /** Classic onXContainer values; surfaceVariant pair for unknown. */
        @ColorInt
        private fun classicTextColorFor(todo: String?): Int = when (todo?.uppercase()) {
            "DONE" -> 0xFF002105.toInt()
            "DOING", "IN-PROGRESS", "STARTED" -> 0xFF0C2B27.toInt()
            "VIBING" -> 0xFF00363C.toInt()
            "NEXT" -> 0xFF001D34.toInt()
            "TODO" -> 0xFF3C403E.toInt()
            "WAIT", "WAITING" -> 0xFF2E1500.toInt()
            "HOLD" -> 0xFF0D1F30.toInt()
            "SANDBAGGING" -> 0xFF35280F.toInt()
            "PROJ" -> 0xFF27125E.toInt()
            "AREA" -> 0xFF333900.toInt()
            "MAYBE" -> 0xFF2A2744.toInt()
            "DROPPED" -> 0xFF4A0E26.toInt()
            // onSurface (not onSurfaceVariant), matching OrgStateChip's
            // contrast-safe choice for the dim grey cancelled chip.
            "CANCELLED", "CANCELED" -> 0xFF303332.toInt()
            else -> 0xFF5C605E.toInt()
        }

        /**
         * Kraft Ledger ink foregrounds on the #EEEAE0 base — same values as
         * the in-app KraftLedgerExtendedColors keyword pairs.
         */
        @ColorInt
        private fun kraftTextColorFor(todo: String?): Int = when (todo?.uppercase()) {
            "DONE" -> 0xFF386838.toInt()
            "DOING", "IN-PROGRESS", "STARTED" -> 0xFF1E6B63.toInt()
            "VIBING" -> 0xFF6D3F85.toInt()
            "NEXT" -> 0xFF2B5A9A.toInt()
            "TODO" -> 0xFF5A5446.toInt()
            "WAIT", "WAITING" -> 0xFF85560A.toInt()
            "HOLD" -> 0xFF33517A.toInt()
            "SANDBAGGING" -> 0xFF6E4E1E.toInt()
            "PROJ" -> 0xFF5A3E8F.toInt()
            "AREA" -> 0xFF5C6B1E.toInt()
            "MAYBE" -> 0xFF56517A.toInt()
            "DROPPED" -> 0xFF8F3552.toInt()
            "CANCELLED", "CANCELED" -> 0xFF6B675C.toInt()
            else -> 0xFF5A5446.toInt()
        }
    }
}
