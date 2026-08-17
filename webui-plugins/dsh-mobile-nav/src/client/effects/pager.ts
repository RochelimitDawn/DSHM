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
export const PAGER_BREAKPOINT = '(max-width: 1023px)'

/** The pager page the frame is resting on (mirror attribute on the frame). */
export const PAGE_ATTR = 'data-dshm-page'

/** Pager page names (the mirror values of PAGE_ATTR). */
export type MobilePage = 'sidebar' | 'chat'

/** Wait after the last scroll event before the pager settles. */
const SCROLL_SETTLE_MS = 200

/** The sidebar shell's collapse toggle labels (zh / en) — clicking it while
 *  the sidebar is expanded must NOT collapse it to the rail (which would
 *  unload its content); it flips back to the chat page instead. */
const SIDEBAR_COLLAPSE_LABELS = new Set(['收起侧边栏', 'Collapse sidebar'])

/**
 * The composer's model-name label (the first span of the model TRIGGER
 * button — pinned via aria-haspopup='menu' so the open picker's option
 * rows are never mistaken for it). Its overflow drives the marquee.
 */
const MODEL_LABEL_SELECTOR =
  "[data-composer-card] [data-slot='conversation.input.model'] button[aria-haspopup='menu'] > span:first-child"

/** The gap between marquee repetitions (px). */
const MARQUEE_GAP_PX = 32

/** The AppFrame element: the `[data-mobile-nav="frame"]` marker set by the shell overlay. */
function findFrame(): HTMLElement | null {
  return document.querySelector('[data-mobile-nav="frame"]')
}

/** The pager's chat-page snap position: the rendered width of the sidebar page. */
function chatPageLeft(frame: HTMLElement): number {
  const sidebar = frame.firstElementChild
  if (sidebar instanceof HTMLElement && sidebar.offsetWidth > 0) return sidebar.offsetWidth
  return frame.clientWidth
}

/** Callbacks the controller needs from the apply world. */
export interface PagerControllerOptions {
  /** Expand the sidebar panel (frame-owned layout action). */
  toggleSidebar: () => void
}

/** The DOM-side pager controller (see module doc). */
export class PagerController {
  readonly #options: PagerControllerOptions
  #frame: HTMLElement | null = null
  #mql: MediaQueryList | null = null
  #frameObserver: MutationObserver | null = null
  #rootObserver: MutationObserver | null = null
  #composerObserver: MutationObserver | null = null
  #marqueeLabel: HTMLElement | null = null
  #marqueeRO: ResizeObserver | null = null
  #marqueeFrame: number | null = null
  #keyboardFrame: number | null = null
  #mountFrame: number | null = null
  #resizeTimer: number | null = null
  #settleTimer: number | null = null
  #expandPending = false
  #retryCount = 0
  #mounted = false
  #disposed = false

  constructor(options: PagerControllerOptions) {
    this.#options = options
  }

  /** Return to the chat page (a session picked in the sidebar). Pure scroll. */
  returnToChat(): void {
    this.#placeOnChat('smooth')
  }

  /** Install the controller; idempotent. */
  mount(): void {
    if (this.#mounted || this.#disposed) return
    const frame = findFrame()
    // The frame marker is set by the shell overlay (a React component) after
    // this effect runs — retry for a few seconds until the frame exists.
    if (frame === null) {
      if (this.#retryCount < 40) {
        this.#retryCount++
        window.setTimeout(() => this.mount(), 100)
      }
      return
    }
    this.#mounted = true
    this.#frame = frame

    this.#mql = window.matchMedia(PAGER_BREAKPOINT)
    this.#mql.addEventListener('change', this.#onBreakpointChange)

    // Keyboard inset: the visual viewport shrinks when the OS keyboard opens.
    const vv = window.visualViewport
    vv?.addEventListener('resize', this.#requestKeyboard)
    vv?.addEventListener('scroll', this.#requestKeyboard)

    // Keep the active page in place when the viewport width changes within a
    // breakpoint side (rotation / split-screen reflows the page tracks).
    window.addEventListener('resize', this.#onWindowResize)

    // A tap on the exposed chat card (while the pager rests on the sidebar
    // page) returns to the chat page — PiUI's overlay behavior.
    document.addEventListener('click', this.#onDocClickCapture, true)

    const root = document.getElementById('root')
    if (root !== null) {
      this.#rootObserver = new MutationObserver(() => { this.#ensureFrameObserver() })
      this.#rootObserver.observe(root, { childList: true })
      this.#composerObserver = new MutationObserver(() => { this.#requestMarqueeSync() })
      this.#composerObserver.observe(root, {
        childList: true,
        subtree: true,
        characterData: true,
      })
    }
    if (typeof ResizeObserver !== 'undefined') {
      this.#marqueeRO = new ResizeObserver(() => { this.#requestMarqueeSync() })
    }
    this.#ensureFrameObserver()
    this.#requestMarqueeSync()

    // The always-open phone layout: expand the sidebar once, then start on
    // the CHAT page.
    this.#ensureSidebarOpen()
    this.#placeOnChat('auto')
    this.#mountFrame = requestAnimationFrame(() => {
      this.#mountFrame = null
      this.#ensureSidebarOpen()
      this.#placeOnChat('auto')
    })
  }

  /** Remove every DOM effect; safe to call twice. */
  dispose(): void {
    if (!this.#mounted || this.#disposed) return
    this.#disposed = true
    this.#mounted = false
    this.#frameObserver?.disconnect()
    this.#frameObserver = null
    this.#rootObserver?.disconnect()
    this.#rootObserver = null
    this.#composerObserver?.disconnect()
    this.#composerObserver = null
    this.#marqueeRO?.disconnect()
    this.#marqueeRO = null
    if (this.#marqueeLabel !== null) {
      const label = this.#marqueeLabel
      label.removeAttribute('data-dshm-marquee')
      label.style.removeProperty('--dshm-marquee-duration')
      const runner = label.firstElementChild
      if (runner !== null && runner.hasAttribute('data-dshm-marquee-runner')) {
        const original = runner.firstElementChild?.firstChild ?? null
        runner.remove()
        if (original !== null) label.append(original)
      }
    }
    this.#marqueeLabel = null
    this.#mql?.removeEventListener('change', this.#onBreakpointChange)
    this.#mql = null
    window.removeEventListener('resize', this.#onWindowResize)
    window.visualViewport?.removeEventListener('resize', this.#requestKeyboard)
    window.visualViewport?.removeEventListener('scroll', this.#requestKeyboard)
    document.removeEventListener('click', this.#onDocClickCapture, true)
    for (const timer of [this.#keyboardFrame, this.#mountFrame, this.#resizeTimer, this.#settleTimer, this.#marqueeFrame]) {
      if (timer !== null) (timer === this.#keyboardFrame || timer === this.#mountFrame || timer === this.#marqueeFrame ? cancelAnimationFrame : window.clearTimeout)(timer)
    }
    this.#keyboardFrame = null
    this.#mountFrame = null
    this.#resizeTimer = null
    this.#settleTimer = null
    this.#marqueeFrame = null
    const frame = this.#frame
    if (frame !== null) {
      frame.removeEventListener('scroll', this.#onPagerScroll)
      frame.removeAttribute(PAGE_ATTR)
      frame.style.removeProperty('--dshm-keyboard-inset')
      for (const prop of ['--dshm-rotate', '--dshm-scale', '--dshm-offset-x', '--dshm-origin-x']) {
        frame.style.removeProperty(prop)
      }
    }
    this.#frame = null
  }

  /** The always-open phone layout expands the docked sidebar once when the
   *  viewport crosses into the mobile breakpoint (AppFrame auto-collapses it
   *  to the rail there). The request is idempotent. */
  readonly #ensureSidebarOpen = (): void => {
    if (!(this.#mql?.matches ?? false)) return
    const frame = findFrame()
    if (frame === null) return
    if (!frame.hasAttribute('data-sidebar-collapsed')) {
      this.#expandPending = false
      return
    }
    if (this.#expandPending) return
    this.#expandPending = true
    this.#options.toggleSidebar()
  }

  /** Scroll the pager to the chat page and mirror the resting page. */
  readonly #placeOnChat = (behavior: ScrollBehavior): void => {
    const frame = findFrame()
    const mobile = this.#mql?.matches ?? false
    if (frame === null || !mobile) return
    const chatLeft = chatPageLeft(frame)
    if (chatLeft <= 0) return
    if (Math.abs(frame.scrollLeft - chatLeft) > 2) {
      frame.scrollTo({ left: chatLeft, behavior })
    }
    this.#mirrorPage(frame, 'chat')
    this.#updateFlipVars(frame)
  }

  /** Mirror the page the pager is resting on (scroll position decides). */
  readonly #mirrorPage = (frame: HTMLElement, hint?: MobilePage): void => {
    const chatLeft = chatPageLeft(frame)
    const page: MobilePage = chatLeft <= 0
      ? (hint ?? 'chat')
      : frame.scrollLeft < chatLeft / 2 ? 'sidebar' : 'chat'
    frame.setAttribute(PAGE_ATTR, page)
  }

  /** State flips no longer drive the pager (the page is user-driven). */
  readonly #onFrameCollapseChange = (): void => {
    if (!findFrame()?.hasAttribute('data-sidebar-collapsed')) this.#expandPending = false
  }

  readonly #ensureFrameObserver = (): void => {
    if (this.#frameObserver !== null) return
    const frame = findFrame()
    if (frame === null) return
    this.#frame = frame
    this.#frameObserver = new MutationObserver(this.#onFrameCollapseChange)
    this.#frameObserver.observe(frame, {
      attributes: true,
      attributeFilter: ['data-sidebar-collapsed'],
    })
    frame.addEventListener('scroll', this.#onPagerScroll, { passive: true })
    this.#ensureSidebarOpen()
    this.#placeOnChat('auto')
  }

  /** Crossing the breakpoint: entering mobile re-expands the sidebar and
   *  places the pager on the chat page; leaving clears the 3D flip vars. */
  readonly #onBreakpointChange = (): void => {
    const mobile = this.#mql?.matches ?? false
    const frame = findFrame()
    if (!mobile) {
      for (const prop of ['--dshm-rotate', '--dshm-scale', '--dshm-offset-x', '--dshm-origin-x']) {
        frame?.style.removeProperty(prop)
      }
      frame?.removeAttribute(PAGE_ATTR)
      return
    }
    this.#ensureSidebarOpen()
    this.#placeOnChat('auto')
  }

  /** Width reflow within one breakpoint side: keep the active page put. */
  readonly #onWindowResize = (): void => {
    if (this.#resizeTimer !== null) return
    this.#resizeTimer = window.setTimeout(() => {
      this.#resizeTimer = null
      const frame = findFrame()
      const mobile = this.#mql?.matches ?? false
      if (frame === null || !mobile) return
      const chatLeft = chatPageLeft(frame)
      if (chatLeft <= 0) return
      const onChat = frame.scrollLeft >= chatLeft / 2
      frame.scrollTo({ left: onChat ? chatLeft : 0, behavior: 'auto' })
      this.#mirrorPage(frame)
      this.#updateFlipVars(frame)
      this.#requestMarqueeSync()
    }, 120)
  }

  /** Live pager driver: PiUI's 3D flip vars follow the scroll, and once the
   *  scroll settles the pager re-snaps to the nearest whole page. */
  readonly #onPagerScroll = (): void => {
    const frame = findFrame()
    const mobile = this.#mql?.matches ?? false
    if (frame === null || !mobile) return
    this.#updateFlipVars(frame)
    this.#mirrorPage(frame)
    if (this.#settleTimer !== null) window.clearTimeout(this.#settleTimer)
    this.#settleTimer = window.setTimeout(() => {
      this.#settleTimer = null
      this.#settlePager()
    }, SCROLL_SETTLE_MS)
  }

  /** PiUI's flip: progress -1 (sidebar page) … 0 (chat page). */
  readonly #updateFlipVars = (frame: HTMLElement): void => {
    const chatLeft = chatPageLeft(frame)
    if (chatLeft <= 0) return
    const progress = Math.max(-1, Math.min(1, (frame.scrollLeft - chatLeft) / chatLeft))
    const abs = Math.abs(progress)
    const right = Math.max(0, progress)
    frame.style.setProperty('--dshm-rotate', `${progress * 10}deg`)
    frame.style.setProperty('--dshm-scale', `${1 - abs * 0.06}`)
    frame.style.setProperty('--dshm-offset-x', `${right * right * -48}px`)
    frame.style.setProperty('--dshm-origin-x', `${50 - progress * 50}%`)
  }

  readonly #settlePager = (): void => {
    const frame = findFrame()
    const mobile = this.#mql?.matches ?? false
    if (frame === null || !mobile) return
    const chatLeft = chatPageLeft(frame)
    if (chatLeft <= 0) return
    const left = frame.scrollLeft
    const nearest: MobilePage = left < chatLeft / 2 ? 'sidebar' : 'chat'
    const target = nearest === 'sidebar' ? 0 : chatLeft
    if (Math.abs(left - target) > 4) {
      frame.scrollTo({ left: target, behavior: 'smooth' })
    }
    this.#mirrorPage(frame)
  }

  /** A tap on the exposed chat card returns to the chat page; the sidebar's
   *  own collapse toggle is intercepted the same way (collapsing to the rail
   *  would unload the sidebar content, so it flips back to the chat page). */
  readonly #onDocClickCapture = (event: MouseEvent): void => {
    const target = event.target
    if (!(target instanceof Element)) return
    const frame = findFrame()
    const mobile = this.#mql?.matches ?? false
    if (frame === null || !mobile) return
    const chatLeft = chatPageLeft(frame)
    if (chatLeft <= 0) return
    const sidebarCol = frame.firstElementChild
    if (sidebarCol instanceof Element && sidebarCol.contains(target)) {
      const btn = target.closest('button')
      if (btn !== null && SIDEBAR_COLLAPSE_LABELS.has(btn.getAttribute('aria-label') ?? '')) {
        event.preventDefault()
        event.stopPropagation()
        this.#placeOnChat('smooth')
        return
      }
    }
    if (frame.scrollLeft >= chatLeft / 2) return
    const chatCard = frame.children[1]
    if (chatCard instanceof Element && chatCard.contains(target)) {
      this.#placeOnChat('smooth')
    }
  }

  readonly #requestKeyboard = (): void => {
    if (this.#keyboardFrame !== null) return
    this.#keyboardFrame = requestAnimationFrame(() => {
      this.#keyboardFrame = null
      this.#updateKeyboardInset()
    })
  }

  readonly #updateKeyboardInset = (): void => {
    const frame = this.#frame
    if (frame === null) return
    const vv = window.visualViewport
    const inset = vv !== null && vv.height < window.innerHeight
      ? Math.max(0, window.innerHeight - vv.height - vv.offsetTop)
      : 0
    frame.style.setProperty('--dshm-keyboard-inset', `${inset}px`)
  }

  /** Model-name marquee: re-measure on the next frame. */
  readonly #requestMarqueeSync = (): void => {
    if (this.#marqueeFrame !== null) return
    this.#marqueeFrame = requestAnimationFrame(() => {
      this.#marqueeFrame = null
      this.#syncMarquee()
    })
  }

  /** Measure the model-name label: when the name overflows its capped width,
   *  wrap a DOUBLE copy of the text in a transform layer and tag the label
   *  with data-dshm-marquee + --dshm-marquee-duration — the CSS slides the
   *  runner by -50% (one text width + one gap) on the compositor and loops. */
  readonly #syncMarquee = (): void => {
    const label = document.querySelector<HTMLElement>(MODEL_LABEL_SELECTOR)
    if (label !== this.#marqueeLabel) {
      this.#marqueeRO?.disconnect()
      this.#marqueeLabel = label
      if (label !== null) this.#marqueeRO?.observe(label)
    }
    if (label === null) return
    const runner = label.firstElementChild !== null
        && label.firstElementChild.hasAttribute('data-dshm-marquee-runner')
      ? label.firstElementChild
      : null
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const overflow = label.scrollWidth - label.clientWidth
    if (overflow > 0 && !reduceMotion) {
      if (runner === null) {
        const nodes = Array.from(label.childNodes)
        const layer = document.createElement('span')
        layer.setAttribute('data-dshm-marquee-runner', '')
        for (const node of nodes) {
          const item = document.createElement('span')
          item.setAttribute('data-dshm-marquee-item', '')
          item.append(node)
          layer.append(item)
        }
        for (const node of nodes) {
          const item = document.createElement('span')
          item.setAttribute('data-dshm-marquee-item', '')
          item.append(node.cloneNode(true))
          layer.append(item)
        }
        label.append(layer)
      }
      label.dataset.dshmMarquee = ''
      const textWidth = (label.scrollWidth - MARQUEE_GAP_PX * 2) / 2
      label.style.setProperty('--dshm-marquee-duration', `${Math.max(5, Math.round((textWidth + MARQUEE_GAP_PX) / 50))}s`)
    } else {
      delete label.dataset.dshmMarquee
      label.style.removeProperty('--dshm-marquee-duration')
      if (runner !== null) {
        const original = runner.firstElementChild?.firstChild ?? null
        runner.remove()
        if (original !== null) label.append(original)
      }
    }
  }
}
