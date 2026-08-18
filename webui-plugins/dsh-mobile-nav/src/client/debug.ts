import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
/**
 * Debug badge — ?mobile-nav-debug=1
 * Renders a live state overlay (URL, viewport, media queries, shell chrome,
 * aionui columns, genui cards, captured errors) so a phone-side repro can be
 * diagnosed without guessing. No-op unless the query param is present.
 */
export function installDebugBadge(ctx: ClientContext): void {
  ctx.effect(() => {
    if (!new URLSearchParams(location.search).has('mobile-nav-debug')) return () => {}
    const errors: string[] = []
    const onError = (event: ErrorEvent) => errors.push(`ERR ${event.message.slice(0, 120)}`)
    const onRejection = (event: PromiseRejectionEvent) => errors.push(`REJ ${String(event.reason).slice(0, 120)}`)
    window.addEventListener('error', onError)
    window.addEventListener('unhandledrejection', onRejection)

    const badge = document.createElement('div')
    badge.style.cssText = [
      'position:fixed', 'top:40px', 'right:6px', 'z-index:2147483000',
      'background:rgba(0,0,0,.82)', 'color:#fff', 'font:11px/1.5 ui-monospace,monospace',
      'padding:8px 10px', 'border-radius:8px', 'max-width:94vw', 'max-height:70vh',
      'overflow:auto', 'white-space:pre-wrap', 'pointer-events:none',
    ].join(';')

    const read = (): string => {
      const q = (sel: string) => !!document.querySelector(sel)
      const vis = (sel: string) => {
        const el = document.querySelector<HTMLElement>(sel)
        return el === null ? 'absent' : getComputedStyle(el).visibility
      }
      const frame = document.querySelector<HTMLElement>('[data-mobile-nav="frame"]')
      return [
        `URL ${location.pathname}${location.search}`,
        `W ${innerWidth} x ${innerHeight} dpr ${devicePixelRatio}`,
        `mq≤1023 ${matchMedia('(max-width: 1023px)').matches}  mq≥1024 ${matchMedia('(min-width: 1024px)').matches}`,
        `css ${q('style[data-plugin-css*="mobile"]')}  frame ${!!frame}`,
        `previewCol ${vis('[data-aionui-preview-col]')}  explorerCol ${vis('[data-aionui-explorer-col]')}`,
        `previewOpen ${frame?.hasAttribute('data-aionui-preview-open') ?? '?'}  explorerOpen ${frame?.hasAttribute('data-aionui-explorer-open') ?? '?'}  previewFull ${frame?.hasAttribute('data-mobile-preview-full') ?? '?'}`,
        `header ${vis('[data-phase] header')}  composer ${q('textarea')}`,
        `genui cards ${document.querySelectorAll('[data-genui]').length}  panel ${q('[data-genui-panel]')}`,
        `phase ${document.querySelector('[data-phase]')?.getAttribute('data-phase') ?? '?'}`,
        `errs ${errors.slice(-5).join(' | ') || 'none'}`,
      ].join('\n')
    }
    const paint = (): void => { badge.textContent = read() }
    paint()
    const observer = new MutationObserver(paint)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true })
    const timer = setInterval(paint, 1500)
    document.body.appendChild(badge)
    return () => {
      window.removeEventListener('error', onError)
      window.removeEventListener('unhandledrejection', onRejection)
      observer.disconnect()
      clearInterval(timer)
      badge.remove()
    }
  }, 'dsh-mobile-nav: debug badge')
}
