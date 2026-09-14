import http.client
import sys

token = open('/data/data/com.termux/files/home/gh_token.txt').read().strip()
art_id = sys.argv[1]

conn = http.client.HTTPSConnection('api.github.com')
conn.request('GET', f'/repos/xiaobai-yp/ZenithThermal-/actions/artifacts/{art_id}/zip',
             headers={'Authorization': f'token {token}', 'User-Agent': 'curl/8'})
resp = conn.getresponse()
url = resp.getheader('Location','')
print(url)
