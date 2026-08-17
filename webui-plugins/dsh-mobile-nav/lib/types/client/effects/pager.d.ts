/**
 * PiUI-style pager controller, ported from @dsh-external/dsh-mobile
 * (lehhair/dsh-mobile) and adapted to the dsh-mobile-nav frame marker.
 *
 * The mobile layout follows PiUI's chat pager: the STOCK AppFrame becomes a
 * horizontal scroll-snap pager whose columns are two pages — an always-open
 * sidebar page (the session/history list) and a full-width chat page. The
 * frame's own state is only touched to expand the auto-collapsed sidebar ONCE
 * below the breakpoint (the AppFrame collapses it to the rail on narrow
 * viewports); from then on the pager position is fully user-driven: the app
 * starts on the chat page, a click on the exposed chat card flips back to it,
 * and picking a session in the sidebar returns to it. The sidebar column keeps
 * its full content rendered at all times (a swipe is never state-synced, so it
 * never re-renders).
 *
 * Everything it installs is removed by dispose(); every CSS rule it depends on
 * is scoped under the frame's `data-mobile-nav="frame"` marker.
 */
/** The narrow breakpoint the pager keys off (the shell's sub-desktop range). */
export declare const PAGER_BREAKPOINT = "(max-width: 1023px)";
/** The pager page the frame is resting on (mirror attribute on the frame). */
export declare const PAGE_ATTR = "data-dshm-page";
/** Pager page names (the mirror values of PAGE_ATTR). */
export type MobilePage = 'sidebar' | 'chat';
/** Callbacks the controller needs from the apply world. */
export interface PagerControllerOptions {
    /** Expand the sidebar panel (frame-owned layout action). */
    toggleSidebar: () => void;
}
/** The DOM-side pager controller (see module doc). */
export declare class PagerController {
    #private;
    constructor(options: PagerControllerOptions);
    /** Return to the chat page (a session picked in the sidebar). Pure scroll. */
    returnToChat(): void;
    /** Install the controller; idempotent. */
    mount(): void;
    /** Remove every DOM effect; safe to call twice. */
    dispose(): void;
}
//# sourceMappingURL=pager.d.ts.map