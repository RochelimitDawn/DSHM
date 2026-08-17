// layout — split from src/client/mobile.css.ts (2026-08-16), order preserved.
// PiUI pager structure ported from @dsh-external/dsh-mobile (lehhair/dsh-mobile)
// and adapted to the `[data-mobile-nav="frame"]` marker + 1023px breakpoint.
// Do not reorder: styles/index.ts concatenates in this exact order.

export const LAYOUT_CSS = `/* ---------- mobile-only layout ---------- */

/* --- PiUI pager tokens (scoped to the frame) --- */
[data-mobile-nav="frame"] {
  /* Safe-area insets (notched phones, standalone PWA). env() resolves to
     0px in browsers without the concept. */
  --dshm-safe-top: env(safe-area-inset-top, 0px);
  --dshm-safe-right: env(safe-area-inset-right, 0px);
  --dshm-safe-bottom: env(safe-area-inset-bottom, 0px);
  --dshm-safe-left: env(safe-area-inset-left, 0px);
  /* Virtual-keyboard inset, driven by the pager controller (visualViewport). */
  --dshm-keyboard-inset: 0px;
  /* Sidebar page width (phones): at least the sidebar's own 280px content
     width, at most 360px, with ~70vw — the chat card then shows only a
     sliver on the right (PiUI's overlayWidth idea). Wide mobile screens
     (561-1023px) override this token below so the page grows toward half
     the viewport. */
  --dshm-sidebar-width: clamp(280px, 70vw, 360px);
  /* PiUI-style 3D flip of the chat card, driven by the controller on scroll. */
  --dshm-rotate: 0deg;
  --dshm-scale: 1;
  --dshm-offset-x: 0px;
  --dshm-origin-x: 50% 50%;
  -webkit-tap-highlight-color: transparent;
}

@media (max-width: 1023px) {
  /* --- Phone chrome ---
     The system status bar stays visible (no fullscreen). Two adjustments
     make it behave:
     - touch-action: manipulation kills double-tap-to-zoom (and the 300ms
       tap delay) while keeping pan and pinch zoom.
     - With the client's viewport-fit=cover, env(safe-area-inset-top) is the
       status bar / notch height; the rules below push the app content below
       it so the status bar never covers anything. */
  html,
  body {
    touch-action: manipulation !important;
  }

  /* --- PiUI pager (sub-desktop viewports) ---
     The AppFrame keeps at least one of its two data attributes in every
     state; the union selects the frame on any viewport. Reflow the grid
     tracks into two snap pages: the sidebar page at the half-open width,
     the chat page full-width (so the chat stays visible beside the
     sidebar), the details track dropped (no phone use). */
  [data-mobile-nav="frame"] {
    position: relative !important;
    grid-template-columns: var(--dshm-sidebar-width) 100% 0 !important;
    overflow-x: auto;
    overflow-y: hidden;
    overscroll-behavior-x: contain;
    scroll-snap-type: x mandatory;
    scrollbar-width: none;
    /* The top padding clears the status bar / notch for every in-flow
       surface (session header, messages, composer). */
    padding-top: env(safe-area-inset-top, 0px) !important;
  }

  /* Hide the pager's own horizontal scrollbar on every engine. */
  [data-mobile-nav="frame"]::-webkit-scrollbar {
    width: 0;
    height: 0;
    display: none;
  }

  /* The two live pages snap to the scrollport start (scroll-snap-stop keeps
     a fast swipe from skipping a page). */
  [data-mobile-nav="frame"] > :nth-child(-n+2) {
    scroll-snap-align: start;
    scroll-snap-stop: always;
  }

  /* Sidebar page: a plain page sharing the chat background — flat, no card
     treatment and no divider line against the chat — with safe-area top so
     its controls clear the notch. The chat card's radius and shadow are
     what separate the two (PiUI). */
  [data-mobile-nav="frame"] > :first-child {
    position: static !important;
    inset: auto !important;
    background: var(--dsw-alias-bg-base, #ffffff);
    border-right: none !important;
    padding-top: var(--dshm-safe-top);
    z-index: auto !important;
    transform: none !important;
    transition: none !important;
    max-width: none !important;
  }

  /* The sidebar shell's own fill is reset to transparent — across the one or
     two wrapper levels the shell tree may insert between the frame column and
     the sidebar root — so every layer of the sidebar page matches the chat
     background. Deeper elements keep their own states untouched. */
  [data-mobile-nav="frame"] > :first-child > :first-child,
  [data-mobile-nav="frame"] > :first-child > :first-child > :first-child {
    background: transparent;
  }

  /* The sidebar shell freezes its content at the expanded desktop width
     (inline style); the phone layout keeps the sidebar open, so that frozen
     width would leave the right side of the page empty on wide screens.
     Min-width (not width) stretches the shell to fill the page column. */
  [data-mobile-nav="frame"] > :first-child div[style*='width'] {
    min-width: 100%;
  }

  /* The session list's bottom scroll fade is a desktop affordance; on the
     always-open phone sidebar it just dims the tail of the list. */
  [data-mobile-nav="frame"] > :first-child div:has(> [role='tree']) > span {
    display: none;
  }

  /* Chat page: the PiUI rounded card — rounded and shadowed, clipped to the
     radius, no border so no divider line sits between it and the sidebar.
     On the CHAT page (data-dshm-page=chat on the frame, kept by the
     controller) the card goes full-bleed — square corners, no shadow, edge
     to edge. */
  [data-mobile-nav="frame"] > :nth-child(2) {
    border: none;
    border-radius: 16px;
    overflow: hidden;
    background: var(--dsw-alias-bg-base, #ffffff);
    box-shadow: 0 6px 28px color-mix(in srgb, var(--dsw-static-neutral-1000) 16%, transparent);
    transform: translate3d(var(--dshm-offset-x, 0px), 0, 0)
      rotateY(var(--dshm-rotate, 0deg))
      scale(var(--dshm-scale, 1));
    transform-origin: var(--dshm-origin-x, 50% 50%);
    transform-style: preserve-3d;
    backface-visibility: hidden;
    will-change: transform;
  }

  /* Chat page (resting on the conversation): full-bleed, no card chrome. */
  [data-mobile-nav="frame"][data-dshm-page="chat"] > :nth-child(2) {
    border-radius: 0;
    box-shadow: none;
  }

  /* Drag handles are desktop-only affordances. */
  [data-side="sidebar"],
  [data-side="details"] {
    display: none !important;
  }

  /* Session header: compact with a safe-area top. */
  [data-mobile-nav="frame"] [data-phase="active"] > header {
    padding: calc(6px + var(--dshm-safe-top)) 8px 0 8px;
  }

  /* Conversation column: snugger side clearance for the composer card. */
  [data-mobile-nav="frame"] [data-phase] {
    --dsh-composer-side-clearance: 12px;
  }

  /* Sticky composer seat: lift the input card above the home indicator and
     the virtual keyboard. */
  [data-mobile-nav="frame"] [data-composer-seat] {
    padding-bottom: calc(var(--dshm-safe-bottom) + var(--dshm-keyboard-inset));
  }

  /* Hidden scrollbars are the norm on phones: no reserved gutter, no bars. */
  [data-mobile-nav="frame"] [data-conversation-scroll] {
    scrollbar-gutter: auto;
  }

  /* Touch targets in the control regions get a comfortable hit height. */
  [data-mobile-nav="frame"] [data-phase="active"] > header button,
  [data-mobile-nav="frame"] [data-composer-card] button,
  [data-mobile-nav="frame"] [role="treeitem"] button {
    min-height: 36px;
  }

  /* --- Model-name marquee (PiUI) ---
     The model label absorbs the trigger's free width (see the composer
     rules below); when the name overflows its capped width, the controller
     wraps a double copy in a [data-dshm-marquee-runner] layer and tags the
     label with data-dshm-marquee + --dshm-marquee-duration. */
  [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] button[aria-haspopup="menu"] > span:first-child {
    max-width: 96px;
    min-width: 48px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    transform: translateZ(0);
  }
  [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] button[aria-haspopup="menu"] > span:first-child > [data-dshm-marquee-runner] {
    display: inline-block;
    white-space: nowrap;
  }
  [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] [data-dshm-marquee-runner] > [data-dshm-marquee-item] {
    display: inline-block;
    white-space: nowrap;
    padding-right: 32px;
  }
  [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] button[aria-haspopup="menu"] > span:first-child[data-dshm-marquee] > [data-dshm-marquee-runner] {
    animation: dshm-marquee var(--dshm-marquee-duration, 8s) linear infinite;
  }
  [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] button[aria-haspopup="menu"] > span:first-child[data-dshm-marquee]:hover > [data-dshm-marquee-runner] {
    animation-play-state: paused;
  }
  @keyframes dshm-marquee {
    from { transform: translateX(0); }
    to { transform: translateX(-50%); }
  }

  /* Wide mobile / tablet (561-1023px — landscape phones, large foldables,
     small tablets): the sidebar page grows past the phone cap toward HALF
     the viewport (50vw, capped at 420px). The model-name cap relaxes from
     96px to 160px. */
  @media (min-width: 561px) and (max-width: 1023px) {
    [data-mobile-nav="frame"] {
      --dshm-sidebar-width: clamp(360px, 50vw, 420px);
    }
    [data-mobile-nav="frame"] [data-composer-card] [data-slot="conversation.input.model"] button[aria-haspopup="menu"] > span:first-child {
      max-width: 160px;
    }
  }

  /* --- Conversation text on mobile ---
     The official message flow keeps desktop's 32px side gutters and 16px
     type. On a phone: shrink the type a notch and widen the lines by
     trimming the gutters. */
  [data-phase] [class$="_scrollBody"] {
    scrollbar-gutter: auto !important;
    scrollbar-width: none !important;
  }
  [data-phase] [class$="_scrollBody"]::-webkit-scrollbar {
    display: none !important;
    width: 0 !important;
    height: 0 !important;
  }
  [data-phase] [class$="_actions"] {
    overflow: hidden !important;
  }
  [data-phase] [class$="_actions"] [class$="_timeEnd"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
  }

  [data-phase] [class$="_scroll"]:has(p) {
    padding-left: 20px !important;
    padding-right: 20px !important;
    font-size: 15px !important;
  }
  [data-phase] [class$="_scroll"]:has(p) p,
  [data-phase] [class$="_scroll"]:has(p) li,
  [data-phase] [class$="_scroll"]:has(p) [class*="_text_"] {
    font-size: 15px !important;
  }

  [data-phase] table {
    width: 100% !important;
    max-width: 100% !important;
  }
  [data-phase] th,
  [data-phase] td {
    max-width: none !important;
    min-width: 0 !important;
  }

  [data-phase] [class$="_userStack"],
  [data-phase] [class$="_userStack"] [class$="_bubble"] {
    box-sizing: border-box !important;
    width: fit-content !important;
    max-width: 100% !important;
  }

  /* --- Composer bottom row on mobile ---
     The official row gives the model pill (trailing) flex:0 0 auto, which
     squeezes the agent-permission pill (modes) down to 15px. Let the
     permission pill keep its natural width and let the model pill shrink
     instead. Anchored by the composer card (:has(textarea)). */
  [data-phase] [class*="_card"]:has(textarea) [class$="_row"]:has([class$="_trailing"]) {
    gap: 8px !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_row"]:has([class$="_trailing"]) > :first-child {
    gap: 8px !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_row"]:has([class$="_trailing"]) > :first-child > :nth-child(2) {
    flex: 0 0 auto !important;
    gap: 8px !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_trailing"] {
    flex: 1 1 auto !important;
    gap: 8px !important;
    min-width: 0 !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_root"]:has(> [class$="_trigger"][aria-haspopup="menu"]) {
    flex: 1 1 auto !important;
    min-width: 0 !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_root"]:has(> [class$="_trigger"][aria-haspopup="menu"]) > [class$="_trigger"] {
    width: 100% !important;
    max-width: 100% !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_root"]:has(> [class$="_trigger"][aria-haspopup="menu"]) > [class$="_trigger"] > [class$="_triggerLabel"] {
    flex: 1 1 auto !important;
    min-width: 0 !important;
  }
  [data-phase] [class*="_card"]:has(textarea) [class$="_root"]:has(> [class$="_trigger"]:not([aria-haspopup="menu"])) {
    flex: 0 0 auto !important;
  }

  [data-phase] [class*="_card"]:has(textarea) [class$="_root"]:has(> [class$="_trigger"]) > [class$="_menu"] {
    left: 50% !important;
    right: auto !important;
    transform: translateX(-50%) !important;
  }

  /* --- Session header on mobile ---
     Layout goal: [toggle] [session title] [mode badge] in a row. */
  [data-phase] header {
    padding-right: 12px !important;
  }
  [data-phase] header > :first-child {
    padding-left: 20px !important;
  }
  [data-mobile-nav="toggle"] {
    position: absolute !important;
    left: 8px !important;
    top: 12px !important;
    z-index: 2 !important;
  }
  [data-mobile-nav="files"] {
    position: static !important;
    left: auto !important;
    right: auto !important;
    top: auto !important;
    z-index: auto !important;
  }
  [data-phase] header [class$="_headerActions"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    margin-left: auto !important;
    justify-content: flex-end !important;
  }
  [data-phase] header [class$="_crumbs"] {
    flex: 0 1 auto !important;
    min-width: 0 !important;
    max-width: 24vw !important;
  }
  [data-phase] header [class$="_label"]:has(> svg) {
    order: 1 !important;
    flex: 0 1 auto !important;
    min-width: 0 !important;
    max-width: 42vw !important;
    display: block !important;
    position: relative !important;
    box-sizing: border-box !important;
    padding-left: 18px !important;
    padding-right: 2px !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
  }
  [data-phase] header [class$="_label"]:has(> svg) > svg {
    position: absolute !important;
    left: 0 !important;
    top: 50% !important;
    transform: translateY(-50%) !important;
  }
  [data-phase] header [class$="_root"]:has(> button[class$="_trigger"]) {
    order: 2 !important;
    flex: 0 0 auto !important;
    min-width: max-content !important;
    max-width: none !important;
    white-space: nowrap !important;
    position: static !important;
  }
  [data-phase] header [class$="_root"]:has(> button[class$="_trigger"]) > button,
  [data-phase] header [class$="_root"]:has(> button[class$="_trigger"]) > button * {
    white-space: nowrap !important;
  }
  [data-phase] header [data-mobile-nav="files"] {
    order: 3 !important;
    flex: 0 0 auto !important;
  }
  [data-phase] header > :first-child > :last-child {
    display: none !important;
  }

  /* --- Header popovers on mobile --- */
  [data-phase] header [class$="_menu"] {
    left: 8px !important;
    right: auto !important;
    width: min(336px, calc(100vw - 16px)) !important;
    max-width: none !important;
    max-height: min(420px, calc(100dvh - 120px)) !important;
  }

  /* --- Settings dialog on mobile ---
     Desktop: 800px two-column flex (188px nav + content). Mobile: a
     near-full-width sheet — nav tabs wrap into rows on top, option rows
     stay horizontal. Structural selectors are scoped to the unique
     aria-modal dialog; every settings-specific rule is gated with
     :has(> :first-child > :last-child > button). Requires :has() support
     (Chromium 105+, 2022). */
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) {
    position: absolute !important;
    left: 8px !important;
    top: calc(env(safe-area-inset-top, 0px) + 12px) !important;
    width: calc(100vw - 16px) !important;
    max-width: calc(100vw - 16px) !important;
    height: auto !important;
    max-height: min(800px, calc(100vh - 24px - env(safe-area-inset-top, 0px))) !important;
    max-height: min(800px, calc(100dvh - 24px - env(safe-area-inset-top, 0px))) !important;
    flex-direction: column !important;
    border-radius: 14px !important;
    animation: dsh-mobile-nav-sheet-in .22s var(--ds-ease-out, ease-in-out);
  }
  :has(> [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"]))) > :first-child {
    animation: dsh-mobile-nav-fade .18s var(--ds-ease-out, ease-in-out);
  }
  @media (prefers-reduced-motion: reduce) {
    [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])),
    :has(> [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"]))) > :first-child {
      animation: none !important;
    }
  }
  [aria-modal="true"]:not(:has(> :first-child > :last-child > button)) {
    max-width: calc(100vw - 32px) !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child {
    width: 100% !important;
    flex-direction: row !important;
    align-items: center !important;
    gap: 6px !important;
    padding: 10px 12px 8px !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child > :first-child {
    display: none !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :first-child [class$="_navList"] {
    flex: 1 1 auto !important;
    min-width: 0 !important;
    flex-direction: row !important;
    flex-wrap: wrap !important;
    gap: 6px !important;
    overflow: visible !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) [class$="_header"] {
    flex: 0 0 auto !important;
    justify-content: flex-end !important;
    align-items: center !important;
    gap: 8px !important;
    padding: 0 0 0 4px !important;
    min-height: 40px !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) [class$="_header"] > * {
    margin-left: 0 !important;
    margin-right: 0 !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) [class$="_header"] > :last-child {
    width: 32px !important;
    height: 32px !important;
    border-radius: 50% !important;
    display: inline-flex !important;
    align-items: center !important;
    justify-content: center !important;
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06)) !important;
  }
  [aria-modal="true"] [class$="_cubeRow"] {
    gap: 6px !important;
  }
  [aria-modal="true"] [class$="_cubeRow"] > * {
    flex: 1 1 0 !important;
    flex-direction: row !important;
    align-items: center !important;
    justify-content: center !important;
    gap: 6px !important;
    padding: 10px 8px !important;
    min-height: 0 !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child {
    flex: 1 1 auto !important;
    min-height: 0 !important;
  }
  [aria-modal="true"]:has(> :first-child > :last-child > button):not(:has([role="navigation"])):not(:has([class*="ZuhsRW"])) > :last-child > :last-child {
    padding: 0 12px 24px !important;
  }
`
