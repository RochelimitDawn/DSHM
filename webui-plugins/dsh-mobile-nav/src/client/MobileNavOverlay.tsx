import { useEffect, useLayoutEffect, useState } from 'react'
import type { PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import { IconPanelLeftOutline16 } from '@deepseek-ai/dsh-client-ui-primitives'
import { NS } from './locales.ts'

/** Full props for the shell overlay entry. */
export interface MobileNavOverlayProps extends PropsRuntime<'shell.overlay'>, PropsLocale<typeof NS> {
  /** Bound ctx.layout.toggleSidebar(). */
  toggleSidebar: () => void
}

/** Same breakpoint as the shell's SIDEBAR_AUTO_COLLAPSE (viewport < 1024). */
const MOBILE_QUERY = '(max-width: 1023px)'

/** Live matchMedia hook for the narrow breakpoint. */
function useMobile(): boolean {
  const [mobile, setMobile] = useState(() => window.matchMedia(MOBILE_QUERY).matches)
  useEffect(() => {
    const query = window.matchMedia(MOBILE_QUERY)
    const onChange = (event: MediaQueryListEvent) => setMobile(event.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return mobile
}

/** The AppFrame element: direct parent of the shell overlay layer. */
function findFrame(): HTMLElement | null {
  return document.querySelector('[data-shell-overlay]')?.parentElement ?? null
}

/** Flip the PiUI pager to the full-width chat page (smooth); falls back to
 *  the sidebar toggle when the pager frame is not present. */
function scrollToChat(fallback: () => void): void {
  const frame = document.querySelector<HTMLElement>('[data-mobile-nav="frame"]')
  if (frame === null) {
    fallback()
    return
  }
  const sidebar = frame.firstElementChild
  const chatLeft = sidebar instanceof HTMLElement && sidebar.offsetWidth > 0 ? sidebar.offsetWidth : frame.clientWidth
  frame.scrollTo({ left: chatLeft, behavior: 'smooth' })
}

/** Flip the PiUI pager to the sidebar (history) page (smooth); falls back to
 *  the sidebar toggle when the pager frame is not present. */
function scrollToSidebar(fallback: () => void): void {
  const frame = document.querySelector<HTMLElement>('[data-mobile-nav="frame"]')
  if (frame === null) {
    fallback()
    return
  }
  frame.scrollTo({ left: 0, behavior: 'smooth' })
}

/**
 * Mobile shell overlay: owns the `data-mobile-nav` marker on the AppFrame
 * element (the CSS restructure keys off it), mirrors the frame's collapsed
 * state into React state, and renders the dimmed backdrop plus a floating
 * directory button for the hero/blank phases that have no session header.
 */
export function MobileNavOverlay({ toggleSidebar, t }: MobileNavOverlayProps) {
  const mobile = useMobile()
  const [open, setOpen] = useState(false)
  const [fabVisible, setFabVisible] = useState(false)

  // Frame ownership + open-state mirror. On wide screens this effect is inert:
  // the marker is never set, so the layout is untouched.
  useLayoutEffect(() => {
    if (!mobile) {
      setOpen(false)
      return
    }
    const frame = findFrame()
    if (frame === null) return
    frame.setAttribute('data-mobile-nav', 'frame')
    const sync = () => setOpen(!frame.hasAttribute('data-sidebar-collapsed'))
    sync()
    const observer = new MutationObserver(sync)
    observer.observe(frame, { attributes: true, attributeFilter: ['data-sidebar-collapsed'] })
    return () => {
      observer.disconnect()
      frame.removeAttribute('data-mobile-nav')
      frame.removeAttribute('data-mobile-preview-full')
    }
  }, [mobile])

  // The floating button is a fallback for surfaces without a session header:
  // phase "active" means the header (and its toggle) is rendered already.
  useEffect(() => {
    if (!mobile) {
      setFabVisible(false)
      return
    }
    const sync = () => setFabVisible(document.querySelector('[data-phase="active"]') === null)
    sync()
    const observer = new MutationObserver(sync)
    // childList: the conversation root can be replaced wholesale on session
    // switches, so attribute-only observation would miss the new phase.
    observer.observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['data-phase'],
    })
    return () => observer.disconnect()
  }, [mobile])

  // Escape returns to the chat page (the PiUI pager) — but yields to an open
  // modal dialog (e.g. the settings panel), which owns its own Escape handling.
  useEffect(() => {
    if (!mobile || !open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && document.querySelector('[aria-modal="true"]') === null) scrollToChat(toggleSidebar)
    }
    // Capture phase: run before the settings panel's own document-bubble Escape
    // handler, so the modal is still present when we yield to it.
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [mobile, open])

  // Navigation in the sidebar page hands the screen to the content it just
  // opened: tapping a session row or a plugin takeover entry (task board /
  // ssh) returns the pager to the chat page. Capture phase — the pager flips
  // before the shell or a plugin processes the click, so takeover panels
  // never render under the sidebar page.
  //
  // Deliberately NOT flipped by this rule:
  // - Settings / Session log: their dialogs render INSIDE the sidebar DOM
  //   (portaled into the sidebar); flipping away would slide the dialog
  //   off-screen with it.
  // - Anything while a modal dialog is open: the dialog owns the screen.
  useEffect(() => {
    if (!mobile || !open) return
    const onDrawerClick = (event: MouseEvent) => {
      if (document.querySelector('[aria-modal="true"]') !== null) return
      const target = event.target as HTMLElement | null
      if (target === null) return
      const drawer = document.querySelector<HTMLElement>('[data-mobile-nav="frame"] > :first-child')
      if (drawer === null || !drawer.contains(target)) return
      // A session row's own action buttons — the "Session actions" kebab
      // (delete / rename), revealed on hover / long-press — open an edit
      // menu. Tapping one must NOT count as tapping the row, or the pager
      // would flip and take the just-opened menu with it.
      if (target.closest('[class*="sessionRow"] button') !== null) return
      const navigates = target.closest(
        'button[data-dsh-taskboard-entry], button[data-dsh-ssh-entry], [class*="newSession"], [class*="sessionRow"], [class*="searchResultRow"], [class*="searchResultWorkspace"]',
      )
      if (navigates !== null) scrollToChat(toggleSidebar)
    }
    document.addEventListener('click', onDrawerClick, true)
    return () => document.removeEventListener('click', onDrawerClick, true)
  }, [mobile, open])

  // Fullscreen toggle for the aionui preview sheet. The button is appended
  // INTO the preview column (position: absolute against it), so it rides
  // the sheet's own motion — open animation, geometry transition — locked
  // by construction instead of matching transition curves, and it hides
  // with the sheet automatically. The suite's React re-renders the column
  // content, so a MutationObserver re-appends the button whenever it is
  // wiped. (The sheet is z-index 56, above the overlay layer's z-20
  // stacking context, so a button inside the sheet is never covered.)
  // Clicking toggles the frame's `data-mobile-preview-full` marker; the
  // two SVG icons swap via CSS.
  useEffect(() => {
    if (!mobile) return
    let button: HTMLButtonElement | null = null
    let observer: MutationObserver | null = null
    const onClick = (): void => {
      findFrame()?.toggleAttribute('data-mobile-preview-full')
    }
    const ensure = (): void => {
      const col = document.querySelector('[data-aionui-preview-col]')
      if (col === null) return
      if (button === null) {
        button = document.createElement('button')
        button.type = 'button'
        button.dataset.mobileNav = 'preview-full-toggle'
        button.setAttribute('aria-label', t('previewFullscreen'))
        button.title = t('previewFullscreen')
        button.innerHTML = [
          '<svg class="dsh-mobile-nav-full-in" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
          '<path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
          '</svg>',
          '<svg class="dsh-mobile-nav-full-out" viewBox="0 0 16 16" fill="none" aria-hidden="true">',
          '<path d="M6 2v4H2M10 2v4h4M6 14v-4H2M10 14v-4h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>',
          '</svg>',
        ].join('')
        button.addEventListener('click', onClick)
      }
      if (button.parentElement !== col) col.appendChild(button)
    }
    ensure()
    observer = new MutationObserver(ensure)
    observer.observe(document.body, { childList: true, subtree: true })
    return () => {
      observer.disconnect()
      button?.remove()
    }
  }, [mobile, t])

  // Move the git branch chip (conversation.input.dock) INTO the composer
  // card on mobile: it reads as a stray capsule floating between the dock
  // rows and the input card. Reparenting into the card lets CSS pin it to
  // the card's top-left (the card is position: relative) and give the card
  // a chip row via padding-top. The dock's React re-render restores the
  // chip to the dock, so a MutationObserver re-appends idempotently (same
  // pattern as the preview fullscreen toggle above). When the viewport
  // widens, cleanup moves the chip back to the dock — the desktop layout
  // is untouched.
  useEffect(() => {
    if (!mobile) return
    let observer: MutationObserver | null = null
    const ensure = (): void => {
      const chip = document.querySelector(
        '[data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor]',
      )
      if (chip === null) return
      const card = document.querySelector('textarea')?.closest('[class$="_card"]')
      if (card == null) return
      if (chip.parentElement !== card) card.insertBefore(chip, card.firstChild)
    }
    ensure()
    observer = new MutationObserver(ensure)
    observer.observe(document.body, { childList: true, subtree: true })
    return () => {
      observer?.disconnect()
      const chip = document.querySelector(
        '[data-slot="conversation.input.dock"] [data-gitgraph-chip-anchor]',
      )
      const dock = document.querySelector('[data-slot="conversation.input.dock"]')
      if (chip !== null && dock !== null && chip.parentElement !== dock) dock.appendChild(chip)
    }
  }, [mobile])

  // Settings dialog: move the toolbar (Open configuration file + close)
  // INTO the nav row so it shares ONE line with the category tabs — the
  // official layout gives the toolbar its own row under the tabs, which on
  // a phone leaves a full-width dead gap and pushes the options area down
  // (user feedback 2026-08-16). The toolbar is React-owned, so a
  // MutationObserver re-appends idempotently (same pattern as the chip
  // above). Desktop untouched: this effect only runs while the frame
  // marker is active. The toolbar is anchored by its class suffix — the
  // export dialog (header + description + body) has no nav row, so
  // querying the nav first makes the move a no-op there.
  useEffect(() => {
    if (!mobile) return
    let observer: MutationObserver | null = null
    const ensure = (): void => {
      const dialog = document.querySelector('[aria-modal="true"]')
      if (dialog === null) return
      const nav = dialog.querySelector(':scope > [class$="_nav"]')
      const header = dialog.querySelector('[class$="_header"]')
      if (nav === null || header === null) return
      if (header.parentElement !== nav) nav.appendChild(header)
    }
    ensure()
    observer = new MutationObserver(ensure)
    observer.observe(document.body, { childList: true, subtree: true })
    return () => observer?.disconnect()
  }, [mobile])

  if (!mobile) return null
  return (
    <>
      {fabVisible && (
        <button
          type="button"
          data-mobile-nav="fab"
          aria-label={t('open')}
          title={t('open')}
          onClick={() => scrollToSidebar(toggleSidebar)}
        >
          <IconPanelLeftOutline16 size={18} />
        </button>
      )}
    </>
  )
}
