import requests
import ssl
import json
import re
from bs4 import BeautifulSoup

# Custom Adapter for LMS OpenSSL legacy renegotiation error
class TLSAdapter(requests.adapters.HTTPAdapter):
    def init_poolmanager(self, *args, **kwargs):
        ctx = ssl.create_default_context()
        ctx.options |= ssl.OP_LEGACY_SERVER_CONNECT
        kwargs['ssl_context'] = ctx
        return super(TLSAdapter, self).init_poolmanager(*args, **kwargs)


def scrape_lms_notices():
    session = requests.Session()
    session.mount('https://', TLSAdapter())

    url = 'https://e-cyber.catholic.ac.kr/ilos/community/notice_list.acl'
    base_domain = 'https://e-cyber.catholic.ac.kr'
    
    current_year = "2026"
    results = []
    
    start_idx = 1
    keep_scraping = True

    print(f"Scraping LMS notices for {current_year}...")

    while keep_scraping:
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'X-Requested-With': 'XMLHttpRequest'
        }
        
        data = {
            'SCH_KEY': 'T',
            'SCH_TARGET': '',
            'SCH_VALUE': '',
            'start': str(start_idx),
            'encoding': 'utf-8'
        }
        
        response = session.post(url, headers=headers, data=data)
        if response.status_code != 200:
            break
            
        soup = BeautifulSoup(response.text, 'html.parser')
        tbody = soup.find('tbody')
        if not tbody:
            break
            
        rows = tbody.find_all('tr')
        if not rows:
            break
            
        for row in rows:
            cols = row.find_all('td')
            if len(cols) < 6:
                continue
                
            is_pinned = cols[0].find('img') is not None
            date_str = cols[4].text.strip()
            date_part = date_str.split(' ')[0]
            
            if len(date_part) == 5:
                year = current_year
                formatted_date = f"{current_year}.{date_part}"
            else:
                year = date_part.split('.')[0]
                formatted_date = date_part
                
            if year < current_year and not is_pinned:
                keep_scraping = False
                break
                
            if year == current_year:
                a_tag = cols[2].find('a')
                if a_tag:
                    results.append({
                        'title': a_tag.text.strip(),
                        'date': formatted_date,
                        'author': cols[3].text.strip(),
                        'views': cols[5].text.strip(),
                        'link': base_domain + a_tag['href']
                    })
                    
        if keep_scraping:
            start_idx += 20

    with open('lms_announcements.json', 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=4)
    print(f"Saved {len(results)} LMS notices.\n")


def scrape_main_announcements():
    session = requests.Session()
    url = 'https://www.catholic.ac.kr/ko/campuslife/notice.do'
    
    current_year = "2026"
    results = []
    
    offset = 0
    keep_scraping = True

    print(f"Scraping Main Site notices for {current_year}...")

    while keep_scraping:
        params = {
            'mode': 'list',
            'articleLimit': '10',
            'article.offset': str(offset)
        }
        
        headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
        response = session.get(url, headers=headers, params=params)
        
        if response.status_code != 200:
            break
            
        soup = BeautifulSoup(response.text, 'html.parser')
        tbody = soup.find('tbody')
        if not tbody:
            break
            
        rows = tbody.find_all('tr')
        if not rows:
            break
            
        for row in rows:
            cols = row.find_all('td')
            if len(cols) < 6:
                continue
                
            is_pinned = cols[0].find('span', class_='b-num-notice') is not None
            
            date_str = cols[3].text.strip()
            year = date_str.split('.')[0]
            
            if year < current_year and not is_pinned:
                keep_scraping = False
                break
                
            if year == current_year:
                a_tag = cols[2].find('a', class_='b-title')
                if a_tag:
                    title = " ".join(a_tag.text.split())
                    raw_link = a_tag['href']
                    
                    results.append({
                        'title': title,
                        'category': cols[1].text.strip(),
                        'date': date_str,
                        'author': cols[4].text.strip(),
                        'views': cols[5].text.strip(),
                        'link': url + raw_link
                    })
                    
        if keep_scraping:
            offset += 10

    with open('main_announcements.json', 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=4)
    print(f"Saved {len(results)} Main Site notices.\n")


def scrape_events():
    session = requests.Session()
    url = 'https://www.catholic.ac.kr/ko/campuslife/notice_event.do'
    
    current_year = "2026"
    results = []
    
    offset = 0
    keep_scraping = True

    print(f"Scraping Campus Events for {current_year}...")

    while keep_scraping:
        params = {
            'mode': 'list',
            'articleLimit': '10',
            'article.offset': str(offset)
        }
        
        headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
        response = session.get(url, headers=headers, params=params)
        
        if response.status_code != 200:
            break
            
        soup = BeautifulSoup(response.text, 'html.parser')
        items = soup.select("li.b-img-con-box")
        
        if not items:
            break
            
        for item in items:
            date_span = item.select_one("span.b-date")
            if not date_span:
                continue
                
            date_str = date_span.text.strip()
            year = date_str.split('.')[0]
            
            if year < current_year:
                keep_scraping = False
                break
                
            if year == current_year:
                a_tag = item.select_one("a")
                title_span = item.select_one("div.b-img-title span")
                writer_span = item.select_one("span.b-writer")
                views_span = item.select_one("span.b-view")
                img_tag = item.select_one("div.b-img-box img")

                title = " ".join(title_span.text.split()) if title_span else ""
                raw_link = a_tag["href"] if a_tag and "href" in a_tag.attrs else ""

                results.append({
                    "title": title,
                    "date": date_str,
                    "author": writer_span.text.strip() if writer_span else "",
                    "views": views_span.text.strip() if views_span else "",
                    "image": img_tag["src"] if img_tag and "src" in img_tag.attrs else "",
                    "link": url + raw_link if raw_link.startswith("?") else raw_link
                })
                
        if keep_scraping:
            offset += 10

    with open('events.json', 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=4)
    print(f"Saved {len(results)} events to events.json.\n")


def scrape_outside_events():
    session = requests.Session()
    url = 'https://www.catholic.ac.kr/ko/campuslife/notice_outside.do'
    
    current_year = "2026"
    results = []
    
    offset = 0
    keep_scraping = True

    print(f"Scraping Outside Notices for {current_year}...")

    while keep_scraping:
        params = {
            'mode': 'list',
            'articleLimit': '10',
            'article.offset': str(offset)
        }
        
        headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
        response = session.get(url, headers=headers, params=params)
        
        if response.status_code != 200:
            break
            
        soup = BeautifulSoup(response.text, 'html.parser')
        rows = soup.select("div.bn-list-common01 table tbody tr")
        
        if not rows:
            break
            
        for row in rows:
            cols = row.find_all("td")
            if len(cols) < 5:
                continue
                
            is_pinned = cols[0].find('span', class_='b-num-notice') is not None
            date_str = cols[2].text.strip()
            year = date_str.split('.')[0]
            
            if year < current_year and not is_pinned:
                keep_scraping = False
                break
                
            if year == current_year:
                a_tag = row.select_one("a.b-title")
                title = " ".join(a_tag.text.split()) if a_tag else ""
                raw_link = a_tag["href"] if a_tag and "href" in a_tag.attrs else ""

                results.append({
                    "number": cols[0].text.strip(),
                    "title": title,
                    "date": date_str,
                    "author": cols[3].text.strip(),
                    "views": cols[4].text.strip(),
                    "link": url + raw_link if raw_link.startswith("?") else raw_link
                })
                
        if keep_scraping:
            offset += 10

    with open('events_outside.json', 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=4)
    print(f"Saved {len(results)} outside events to events_outside.json.\n")


# ---------------------------------------------------------------------------
# AI, Electronics & Information Convergence department major sites
# https://{major}.catholic.ac.kr/{major}/community/{board}.do
# ---------------------------------------------------------------------------

DEPARTMENT = "AI전자정보융합"

MAJORS = {
    'csie': '컴퓨터정보공학',
    'mtc': '미디어콘텐츠',
    'datascience': '데이터사이언스',
    'ice': '정보통신전자공학',
    'ai': '인공지능',
}

# Per-major boards: raw URL slug -> normalized category
# Categories: notice, career, news, external (outside events/contests), graduate, recruitment
DEPT_BOARDS = {
    'csie': {
        'notice': 'notice',
        'career': 'career',
        'news': 'news',
    },
    'mtc': {
        'notice': 'notice',
        'news': 'news',
        'notice1': 'external',
    },
    'ice': {
        'notice': 'notice',
        'notice01': 'career',
        'ice-news': 'news',  # currently empty
    },
    'ai': {
        'notice': 'notice',
        'contest': 'external',
        'graduatenotice': 'graduate',
    },
    'datascience': {
        'notice': 'notice',
        'recruitment': 'recruitment',  # change to 'career' if these are job postings
    },
}

DATE_RE = re.compile(r'(\d{4})[.\-/](\d{2})[.\-/](\d{2})')


def _make_link(page_url, origin, href):
    if not href:
        return ""
    if href.startswith('?'):
        return page_url + href
    if href.startswith('/'):
        return origin + href
    return href


def _extract_date(el):
    # Prefer a cell/span that is *only* a date, so dates inside titles don't match
    for c in el.select('span.b-date, td.b-td-date') + el.find_all('td'):
        m = DATE_RE.fullmatch(c.get_text(strip=True))
        if m:
            return m.group(0)
    m = DATE_RE.search(el.get_text(' ', strip=True))
    return m.group(0) if m else None


def _parse_dept_item(el, page_url, origin):
    a_tag = el.select_one('a.b-title') or el.select_one('a[href]')
    if not a_tag:
        return None

    date_str = _extract_date(el)
    if not date_str:
        return None

    title_el = el.select_one('div.b-img-title span') or a_tag
    writer = el.select_one('.b-writer')
    views = el.select_one('.b-view')
    img = el.select_one('div.b-img-box img')

    is_pinned = (el.select_one('.b-num-notice') is not None
                 or 'b-top-box' in (el.get('class') or []))

    item = {
        'title': " ".join(title_el.get_text().split()),
        'date': date_str,
        'author': writer.get_text(strip=True) if writer else "",
        'views': views.get_text(strip=True) if views else "",
        'link': _make_link(page_url, origin, a_tag.get('href', '')),
        'pinned': is_pinned,
    }
    if img and img.get('src'):
        item['image'] = _make_link(page_url, origin, img['src'])
    return item


def scrape_department_board(major, board, category, current_year="2026", max_pages=50):
    session = requests.Session()
    origin = f'https://{major}.catholic.ac.kr'
    url = f'{origin}/{major}/community/{board}.do'

    results = []
    seen = set()
    offset = 0
    keep_scraping = True

    print(f"Scraping {major}/{board} [{category}] ({MAJORS.get(major, major)}) for {current_year}...")

    for _ in range(max_pages):
        if not keep_scraping:
            break

        params = {
            'mode': 'list',
            'articleLimit': '10',
            'article.offset': str(offset)
        }
        headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

        try:
            response = session.get(url, headers=headers, params=params, timeout=15)
        except requests.RequestException as e:
            print(f"  request failed: {e}")
            break
        if response.status_code != 200:
            print(f"  HTTP {response.status_code}, skipping")
            break

        soup = BeautifulSoup(response.text, 'html.parser')
        # Table-style boards (notice/career) or gallery-style boards (news)
        items = soup.select('table tbody tr') or soup.select('li.b-img-con-box')
        if not items:
            break

        new_on_page = 0
        for el in items:
            item = _parse_dept_item(el, url, origin)
            if not item or item['link'] in seen:
                continue
            seen.add(item['link'])
            new_on_page += 1

            year = item['date'][:4]
            if year < current_year and not item['pinned']:
                keep_scraping = False
                break
            if year == current_year:
                item['major'] = major
                item['board'] = board
                item['category'] = category
                results.append(item)

        # Guard against the board ignoring the offset param (same page forever)
        if new_on_page == 0:
            break
        offset += 10

    filename = f'dept_{major}_{board}.json'
    with open(filename, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=4)
    print(f"Saved {len(results)} items to {filename}.\n")
    return results


def scrape_department():
    print(f"=== {DEPARTMENT} ===")
    for major, boards in DEPT_BOARDS.items():
        for board, category in boards.items():
            scrape_department_board(major, board, category)


if __name__ == "__main__":
    scrape_lms_notices()
    scrape_main_announcements()
    scrape_events()
    scrape_outside_events()
    scrape_department()
