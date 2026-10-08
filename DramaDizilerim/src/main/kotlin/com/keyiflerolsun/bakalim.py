# ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

import requests
from parsel import Selector
from re import search
import json

oturum = requests.Session()
oturum.headers.update({
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
})

def test_search(query):
    print(f"\n--- Arama Testi: {query} ---")
    api_url = f"https://dramadizilerim.com/api/search?q={query}&lang=tr"
    resp = oturum.get(api_url)
    if resp.status_code == 200:
        data = resp.json()
        for item in data:
            print(f"- {item.get('title')} | URL: https://dramadizilerim.com/dizi/{item.get('slug')}")
    else:
        print("API Hatası!")

def test_load(url):
    print(f"\n--- Detay Testi: {url} ---")
    resp = oturum.get(url)
    secici = Selector(resp.text)
    
    baslik = secici.css("div.wp-info h1::text, .wp-title::text").get()
    print(f"Başlık: {baslik.strip() if baslik else 'Bulunamadı'}")
    
    bolumler = secici.css("a.wp-ecard[href]")
    print(f"Toplam {len(bolumler)} bölüm bulundu.")
    if len(bolumler) > 0:
        ilk_bolum_url = bolumler[0].css("::attr(href)").get()
        print(f"İlk Bölüm URL: {ilk_bolum_url}")
        return ilk_bolum_url
    return None

def test_load_links(url):
    print(f"\n--- Video Çekme Testi: {url} ---")
    resp = oturum.get(url)
    secici = Selector(resp.text)
    
    # 1. Embed iframe veya lazy player bul
    iframe = secici.css("div.player-wrap iframe::attr(src)").get()
    if not iframe:
        iframe = secici.css("div.lazy-player::attr(data-src)").get()
        
    if not iframe:
        print("Iframe / Lazy player bulunamadı!")
        return
        
    embed_url = iframe if iframe.startswith("http") else f"https://dramadizilerim.com{iframe}"
    print(f"Embed URL: {embed_url}")
    
    # 2. Embed sayfasına git
    oturum.headers.update({"Referer": url})
    embed_resp = oturum.get(embed_url)
    
    # 3. let source = "..." regex ile video URL'sini bul
    video_url_match = search(r'let\s+source\s*=\s*"(https?://[^"]+)"', embed_resp.text)
    if video_url_match:
        video_url = video_url_match.group(1)
        print(f"VİDEO URL BULUNDU: {video_url}")
    else:
        print("❌ Video URL ayıklanamadı!")

if __name__ == "__main__":
    test_search("kral")
    ilk_bolum = test_load("https://dramadizilerim.com/dizi/dublajli-beni-sahiplenen-kral")
    if ilk_bolum:
        test_load_links(ilk_bolum)
