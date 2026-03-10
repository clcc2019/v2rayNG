---
name: v2rayng-ui-style
description: Apply and maintain the V2rayNG UI style system (backgrounds, cards, popups, bottom sheets, list items, night mode). Use whenever editing V2rayNG layouts, colors, drawables, or view logic so new/updated pages match the established design tokens and component treatments.
---

# V2rayNG UI Style

## Overview

Keep V2rayNG pages visually consistent with the current design system: gray page background, white cards, unified card outlines, gray bottom sheets with white action rows, and true‑black night mode.

## Workflow

1. Load the current tokens and mapping from `references/design-tokens.md`.
2. Confirm the scope (which pages/components are being edited).
3. Apply the tokens to layouts/drawables/styles:
   - Backgrounds should use `md_theme_background`.
   - Card surfaces should use `md_theme_surface`.
   - All card strokes should use `color_card_outline`.
4. Verify list cards remain white in light mode, even when selected.
5. For night mode, use true black background and darker surfaces as defined in tokens.

## Rules (Do/Don't)

- Do set list card backgrounds to `md_theme_surface` in code and XML.
- Do keep bottom sheet container gray and action rows white.
- Do keep all card strokes aligned to `color_card_outline`.
- Don’t introduce new ad‑hoc grays; update tokens instead.
- Don’t use gradients or colored overlays in light mode unless explicitly requested.

## Files Most Often Touched

- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values-night/colors.xml`
- `app/src/main/res/values/styles_ui.xml`
- `app/src/main/res/drawable/bg_home_surface.xml`
- `app/src/main/res/drawable/bg_bottom_sheet_surface.xml`
- `app/src/main/res/drawable/bg_bottom_sheet_action_item.xml`
- `app/src/main/res/drawable/bg_popup_surface.xml`
- `app/src/main/res/drawable/bg_menu_surface.xml`
- `app/src/main/res/drawable/bg_list_item_ripple.xml`
- `app/src/main/res/drawable/bg_nav_header_panel.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/fragment_group_server.xml`
- `app/src/main/res/layout/item_recycler_main.xml`
- `app/src/main/java/com/v2ray/ang/ui/MainRecyclerAdapter.kt`

## References

- `references/design-tokens.md`
