import glob, os
from playwright.sync_api import sync_playwright
# Gmail-like: no web font (Roboto/sans fallback), narrow phone
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 340, 'height': 800}, device_scale_factor=2)
    pg.route('**/fonts.googleapis.com/**', lambda r: r.abort())
    for f in sorted(glob.glob('*.html')):
        pg.goto('file://' + os.path.abspath(f)); pg.add_style_tag(content="*{font-family:Roboto,'DejaVu Sans',Arial,sans-serif!important}"); pg.wait_for_timeout(400)
        pg.screenshot(path=f.replace('.html', '-narrow.png'), full_page=True)
    b.close()
