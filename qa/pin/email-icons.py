# Renders the e-mail icons (PNG, hosted with the site: e-mail apps don't show SVG) from the app's own icon paths.
import os, sys
from playwright.sync_api import sync_playwright
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
P = {
 'alert': '<path d="M12 4l9 16H3z"/><path d="M12 10v4.5M12 17.6v.1"/>',
 'wrench': '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.4-.6-.6-2.4z"/>',
 'drop': '<path d="M12 3.5s6 6.6 6 11a6 6 0 0 1-12 0c0-4.4 6-11 6-11z"/>',
 'check': '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
 'bell': '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
 'heart': '<path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.3 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z"/>',
 'cal': '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/><path d="M12 13.2v2.6l1.8 1.1"/>',
 'people': '<circle cx="9" cy="8.5" r="3.2"/><path d="M3.5 19.5c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5"/><circle cx="17" cy="9.5" r="2.4"/><path d="M15.8 14.6c2.4.2 4 1.8 4.6 4.4"/>',
 'pin': '<path d="M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.6"/>',
 'inbox': '<path d="M4 13l2.5-7.5h11L20 13v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"/><path d="M4 13h4.5l1.5 2.5h4l1.5-2.5H20"/>',
 'chat': '<path d="M4.5 19.5l1.2-3.6A7.8 7.8 0 1 1 8.6 18.6z"/><path d="M9 10.5h6M9 13.5h4"/>',
 'spark': '<path d="M12 3.5l1.9 5.6 5.6 1.9-5.6 1.9L12 18.5l-1.9-5.6L4.5 11l5.6-1.9z"/><path d="M18.5 16.5l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z"/>',
 'camera': '<path d="M4 8.5h3l1.6-2.5h6.8L17 8.5h3v10.5H4z"/><circle cx="12" cy="13.5" r="3.3"/>',
 'coins': '<ellipse cx="12" cy="7" rx="6.5" ry="2.8"/><path d="M5.5 7v5c0 1.5 2.9 2.8 6.5 2.8s6.5-1.3 6.5-2.8V7"/><path d="M5.5 12v5c0 1.5 2.9 2.8 6.5 2.8s6.5-1.3 6.5-2.8v-5"/>',
 'quote': '<path d="M5 17.5c0-4 1.5-7 5-9M14 17.5c0-4 1.5-7 5-9"/><circle cx="7.5" cy="16" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/>',
}
T = {'blue': ('#0A78C2', '#E3F0FA'), 'red': ('#C62828', '#FDE8E6'), 'amber': ('#B45F06', '#FFF1DC'), 'green': ('#18804F', '#E2F5EA'), 'coral': ('#D23C32', '#FDE9E6'), 'navy': ('#4A7AA8', '#E6ECF2')}
BADGES = ['check-green', 'alert-red', 'chat-amber', 'cal-blue', 'heart-coral', 'bell-blue', 'wrench-amber', 'drop-blue']
SMALL = ['drop-blue', 'alert-red', 'wrench-amber', 'check-green', 'cal-blue', 'people-blue', 'pin-blue', 'inbox-blue', 'coins-blue', 'heart-coral', 'camera-blue', 'chat-amber', 'spark-amber', 'quote-navy', 'bell-blue']
def svg(icon, tone, badge):
    fg, bg = T[tone]
    inner = '<g fill="none" stroke="%s" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">%s</g>' % (fg, P[icon])
    if badge:
        return '<svg xmlns="http://www.w3.org/2000/svg" width="112" height="112" viewBox="0 0 112 112"><rect width="112" height="112" rx="32" fill="%s"/><g transform="translate(28 28) scale(2.333)">%s</g></svg>' % (bg, inner)
    return '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24">%s</svg>' % inner
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(device_scale_factor=1)
    for name, badge in [(n, True) for n in BADGES] + [(n, False) for n in SMALL]:
        icon, tone = name.split('-'); s = svg(icon, tone, badge); w = 112 if badge else 48
        pg.set_viewport_size({'width': w, 'height': w})
        pg.set_content('<html><body style="margin:0;background:transparent">' + s.replace('width="48" height="48"', 'width="48" height="48" style="display:block"').replace('width="112" height="112" viewBox', 'style="display:block" width="112" height="112" viewBox') + '</body></html>')
        pg.screenshot(path=os.path.join(OUT, name + ('' if badge else '-sm') + '.png'), omit_background=True, clip={'x': 0, 'y': 0, 'width': w, 'height': w})
    b.close()
print('ok')
