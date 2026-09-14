#!/bin/bash
TOKEN=$(cat /data/data/com.termux/files/home/gh_token.txt)
RUN_ID=34774862710

ART_ID=$(curl -s -H "Authorization: token $TOKEN" -H "User-Agent: curl/8" \
  "https://api.github.com/repos/xiaobai-yp/ZenithThermal-/actions/runs/$RUN_ID/artifacts" | python3 -c "import sys,json; print(json.load(sys.stdin)['artifacts'][0]['id'])")
echo "art=$ART_ID"

SIGNED_URL=$(python3 -c "
import http.client
token = open('/data/data/com.termux/files/home/gh_token.txt').read().strip()
conn = http.client.HTTPSConnection('api.github.com')
conn.request('GET', f'/repos/xiaobai-yp/ZenithThermal-/actions/artifacts/{$ART_ID}/zip',
             headers={'Authorization': f'token {token}', 'User-Agent': 'curl/8'})
print(conn.getresponse().getheader('Location',''))
")

cd ~/zenith-daemon-dl
rm -f daemon-56f83fe.zip zenithd-56f83fe
aria2c -x 16 -s 16 --min-split-size=1M -o daemon-56f83fe.zip "$SIGNED_URL" 2>&1 | tail -2
unzip -o daemon-56f83fe.zip -d zen-56f83fe 2>&1 | tail -1
echo "=== bin size ==="
wc -c zen-56f83fe/zenithd
echo "=== systemui in binary? ==="
strings zen-56f83fe/zenithd | grep -c systemui
echo "=== md5 ==="
md5sum zen-56f83fe/zenithd