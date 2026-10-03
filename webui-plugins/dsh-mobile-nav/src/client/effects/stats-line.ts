import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'

  // The official conversation status row (turns / steps / LLM time / TTFT /
  // cache) has a hashed class, so the stylesheet cannot target it directly.
  // Mark the exact row on narrow screens by text: a [class$=_root] that
  // carries the metrics text and no textarea (the composer card also ends in
  // _root and can mention turns in its model line). The CSS then lays the
  // marked row out as ONE horizontally scrolling line with every metric
  // reachable.
export function installStatsLine(ctx: ClientContext): void {
  ctx.effect(() => {
    // Arm on the CURRENT width and re-arm on every width change (see the
    // explorer marker effect for why).
    const narrow = window.matchMedia('(max-width: 1023px)')
    let cleanup: (() => void) | undefined
    const install = (): void => {
      cleanup?.()
      if (!narrow.matches) {
        cleanup = undefined
        return
      }
      // The composer root renders the TPS readout ("TPS 89.4 tok/s") as its
      // own row BELOW the status strip; fold it into the strip so every
      // metric scrolls together. The suite re-renders its own tree, so this
      // must be idempotent and re-run on every mutation.
      const moveTps = (stats: Element): void => {
        if ([...stats.children].some((c) => /^TPS\s+\d/.test((c.textContent ?? '').trim()))) return
        const stack = stats.closest('[class$="_composerStack"]')
        if (stack === null) return
        for (const el of stack.querySelectorAll('div')) {
          const text = (el.textContent ?? '').trim()
          if (!/^TPS\s+\d/.test(text)) continue
          if (el.children.length > 0) continue
          stats.appendChild(el)
          return
        }
      }
      const mark = (): void => {
        for (const root of document.querySelectorAll('[data-phase] [class$="_root"]')) {
          // The status row lives inside the composer stack; message-area
          // blocks can also mention turns/steps and must be skipped.
          if (root.closest('[class$="_composerStack"]') === null) continue
          // The todo plan strip also lives in the composer stack and its root
          // ends in _root. Its items may legitimately contain "步"/"steps" in
          // their text, so never mistake it (or any interactive dock panel)
          // for the stats strip.
          if (root.matches('[data-testid="todo-panel"]')) continue
          if (root.querySelector('button') !== null) continue
          const text = root.textContent ?? ''
          if (!/(turns|steps|\bLLM\b|轮|步)/.test(text)) continue
          if (root.querySelector('textarea') !== null) continue
          root.setAttribute('data-mobile-nav', 'stats')
          moveTps(root)
          return
        }
      }
      const observer = new MutationObserver(mark)
      observer.observe(document.body, { childList: true, subtree: true })
      mark()
      cleanup = () => {
        observer.disconnect()
      }
    }
    install()
    narrow.addEventListener('change', install)
    return () => {
      narrow.removeEventListener('change', install)
      cleanup?.()
    }
  }, 'dsh-mobile-nav: stats line marker')
}
